/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.master.lineage;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.apache.thrift.TProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;

import tachyon.Constants;
import tachyon.TachyonURI;
import tachyon.client.file.TachyonFile;
import tachyon.conf.TachyonConf;
import tachyon.exception.BlockInfoException;
import tachyon.exception.ExceptionMessage;
import tachyon.exception.FileAlreadyCompletedException;
import tachyon.exception.FileAlreadyExistsException;
import tachyon.exception.FileDoesNotExistException;
import tachyon.exception.InvalidFileSizeException;
import tachyon.exception.InvalidPathException;
import tachyon.exception.LineageDeletionException;
import tachyon.exception.LineageDoesNotExistException;
import tachyon.heartbeat.HeartbeatContext;
import tachyon.heartbeat.HeartbeatThread;
import tachyon.job.Job;
import tachyon.master.MasterBase;
import tachyon.master.MasterContext;
import tachyon.master.file.FileSystemMaster;
import tachyon.master.file.options.CompleteFileOptions;
import tachyon.master.file.options.CreateOptions;
import tachyon.master.journal.Journal;
import tachyon.master.journal.JournalOutputStream;
import tachyon.master.journal.JournalProtoUtils;
import tachyon.master.lineage.checkpoint.CheckpointPlan;
import tachyon.master.lineage.checkpoint.CheckpointSchedulingExcecutor;
import tachyon.master.lineage.meta.Lineage;
import tachyon.master.lineage.meta.LineageFile;
import tachyon.master.lineage.meta.LineageFileState;
import tachyon.master.lineage.meta.LineageIdGenerator;
import tachyon.master.lineage.meta.LineageStore;
import tachyon.master.lineage.meta.LineageStoreView;
import tachyon.master.lineage.recompute.RecomputeExecutor;
import tachyon.master.lineage.recompute.RecomputePlanner;
import tachyon.proto.journal.Journal.JournalEntry;
import tachyon.proto.journal.Lineage.AsyncCompleteFileEntry;
import tachyon.proto.journal.Lineage.DeleteLineageEntry;
import tachyon.proto.journal.Lineage.LineageEntry;
import tachyon.proto.journal.Lineage.LineageIdGeneratorEntry;
import tachyon.proto.journal.Lineage.PersistFilesEntry;
import tachyon.proto.journal.Lineage.PersistFilesRequestEntry;
import tachyon.thrift.BlockLocation;
import tachyon.thrift.CheckpointFile;
import tachyon.thrift.CommandType;
import tachyon.thrift.FileBlockInfo;
import tachyon.thrift.LineageCommand;
import tachyon.thrift.LineageInfo;
import tachyon.thrift.LineageMasterClientService;
import tachyon.thrift.LineageMasterWorkerService;
import tachyon.util.IdUtils;
import tachyon.util.io.PathUtils;

/**
 * The lineage master stores the lineage metadata in Tachyon, and it contains the components that
 * manage all lineage-related activities.
 */
public final class LineageMaster extends MasterBase {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final TachyonConf mTachyonConf;
  private final LineageStore mLineageStore;
  private final FileSystemMaster mFileSystemMaster;
  private final LineageIdGenerator mLineageIdGenerator;

  /**
   * The service that checkpoints lineages. We store it here so that it can be accessed from tests.
   */
  @SuppressWarnings("unused")
  private Future<?> mCheckpointExecutionService;
  /**
   * The service that recomputes lineages. We store it here so that it can be accessed from tests.
   */
  @SuppressWarnings("unused")
  private Future<?> mRecomputeExecutionService;

  /** Map from worker to the files to checkpoint on that worker. Used by checkpoint service. */
  private final Map<Long, Set<LineageFile>> mWorkerToCheckpointFile;

  /**
   * @param baseDirectory the base journal directory
   * @return the journal directory for this master
   */
  public static String getJournalDirectory(String baseDirectory) {
    return PathUtils.concatPath(baseDirectory, Constants.LINEAGE_MASTER_NAME);
  }

  /**
   * Creates the lineage master.
   *
   * @param fileSystemMaster the file system master
   * @param journal the journal
   */
  public LineageMaster(FileSystemMaster fileSystemMaster, Journal journal) {
    super(journal, 2);

    mTachyonConf = MasterContext.getConf();
    mFileSystemMaster = Preconditions.checkNotNull(fileSystemMaster);
    mLineageIdGenerator = new LineageIdGenerator();
    mLineageStore = new LineageStore(mLineageIdGenerator);
    mWorkerToCheckpointFile = Maps.newHashMap();
  }

  @Override
  public Map<String, TProcessor> getServices() {
    Map<String, TProcessor> services = new HashMap<String, TProcessor>();
    services.put(
        Constants.LINEAGE_MASTER_CLIENT_SERVICE_NAME,
        new LineageMasterClientService.Processor<LineageMasterClientServiceHandler>(
            new LineageMasterClientServiceHandler(this)));
    services.put(
        Constants.LINEAGE_MASTER_WORKER_SERVICE_NAME,
        new LineageMasterWorkerService.Processor<LineageMasterWorkerServiceHandler>(
            new LineageMasterWorkerServiceHandler(this)));
    return services;
  }

  @Override
  public String getName() {
    return Constants.LINEAGE_MASTER_NAME;
  }

  @Override
  public void processJournalEntry(JournalEntry entry) throws IOException {
    Message innerEntry = JournalProtoUtils.unwrap(entry);
    if (innerEntry instanceof LineageEntry) {
      mLineageStore.addLineageFromJournal((LineageEntry) innerEntry);
    } else if (innerEntry instanceof LineageIdGeneratorEntry) {
      mLineageIdGenerator.initFromJournalEntry((LineageIdGeneratorEntry) innerEntry);
    } else if (innerEntry instanceof AsyncCompleteFileEntry) {
      asyncCompleteFileFromEntry((AsyncCompleteFileEntry) innerEntry);
    } else if (innerEntry instanceof PersistFilesEntry) {
      persistFilesFromEntry((PersistFilesEntry) innerEntry);
    } else if (innerEntry instanceof PersistFilesRequestEntry) {
      requestFilePersistenceFromEntry((PersistFilesRequestEntry) innerEntry);
    } else if (innerEntry instanceof DeleteLineageEntry) {
      deleteLineageFromEntry((DeleteLineageEntry) innerEntry);
    } else {
      throw new IOException(ExceptionMessage.UNEXPECTED_JOURNAL_ENTRY.getMessage(innerEntry));
    }
  }

  @Override
  public void start(boolean isLeader) throws IOException {
    super.start(isLeader);
    if (isLeader) {
      mCheckpointExecutionService =
          getExecutorService().submit(
              new HeartbeatThread(HeartbeatContext.MASTER_CHECKPOINT_SCHEDULING,
                  new CheckpointSchedulingExcecutor(this), mTachyonConf
                      .getInt(Constants.MASTER_LINEAGE_CHECKPOINT_INTERVAL_MS)));
      mRecomputeExecutionService =
          getExecutorService().submit(
              new HeartbeatThread(HeartbeatContext.MASTER_FILE_RECOMPUTATION,
                  new RecomputeExecutor(new RecomputePlanner(mLineageStore, mFileSystemMaster),
                      mFileSystemMaster), mTachyonConf
                      .getInt(Constants.MASTER_LINEAGE_RECOMPUTE_INTERVAL_MS)));
    }
  }

  @Override
  public synchronized void streamToJournalCheckpoint(JournalOutputStream outputStream)
      throws IOException {
    mLineageStore.streamToJournalCheckpoint(outputStream);
    outputStream.writeEntry(mLineageIdGenerator.toJournalEntry());
  }

  /**
   * @return a lineage store view wrapping the contained lineage store
   */
  public LineageStoreView getLineageStoreView() {
    return new LineageStoreView(mLineageStore);
  }

  /**
   * Creates a lineage. It creates a new file for each output file.
   *
   * @param inputFiles the input files
   * @param outputFiles the output files
   * @param job the job
   * @return the id of the created lineage
   * @throws InvalidPathException if the path to the input file is invalid
   * @throws FileAlreadyExistsException if the output file already exists
   * @throws BlockInfoException if fails to create the output file
   */
  public synchronized long createLineage(List<TachyonURI> inputFiles, List<TachyonURI> outputFiles,
      Job job) throws InvalidPathException, FileAlreadyExistsException, BlockInfoException,
      IOException {
    List<TachyonFile> inputTachyonFiles = Lists.newArrayList();
    for (TachyonURI inputFile : inputFiles) {
      long fileId;
      fileId = mFileSystemMaster.getFileId(inputFile);
      if (fileId == IdUtils.INVALID_FILE_ID) {
        throw new InvalidPathException(
            ExceptionMessage.LINEAGE_INPUT_FILE_NOT_EXIST.getMessage(inputFile));
      }
      inputTachyonFiles.add(new TachyonFile(fileId));
    }
    // create output files
    List<LineageFile> outputTachyonFiles = Lists.newArrayList();
    for (TachyonURI outputFile : outputFiles) {
      long fileId;
      // TODO(yupeng): delete the placeholder files if the creation fails.
      // Create the file initialized with block size 1KB as placeholder.
      CreateOptions options =
          new CreateOptions.Builder(MasterContext.getConf()).setRecursive(true)
              .setBlockSizeBytes(Constants.KB).build();
      fileId = mFileSystemMaster.create(outputFile, options);
      outputTachyonFiles.add(new LineageFile(fileId));
    }

    LOG.info("Create lineage of input:{}, output:{}, job:{}", inputTachyonFiles,
        outputTachyonFiles, job);
    long lineageId = mLineageStore.createLineage(inputTachyonFiles, outputTachyonFiles, job);

    writeJournalEntry(mLineageIdGenerator.toJournalEntry());
    writeJournalEntry(mLineageStore.getLineage(lineageId).toJournalEntry());
    flushJournal();
    return lineageId;
  }

  /**
   * Deletes a lineage.
   *
   * @param lineageId id the of lineage
   * @param cascade the flag if to delete all the downstream lineages
   * @return true if the lineage is deleted, false otherwise
   * @throws LineageDoesNotExistException the lineage does not exist
   * @throws LineageDeletionException the lineage deletion fails
   */
  public synchronized boolean deleteLineage(long lineageId, boolean cascade)
      throws LineageDoesNotExistException, LineageDeletionException {
    deleteLineageInternal(lineageId, cascade);
    DeleteLineageEntry deleteLineage = DeleteLineageEntry.newBuilder()
        .setLineageId(lineageId)
        .setCascade(cascade)
        .build();
    writeJournalEntry(JournalEntry.newBuilder().setDeleteLineage(deleteLineage).build());
    flushJournal();
    return true;
  }

  private boolean deleteLineageInternal(long lineageId, boolean cascade)
      throws LineageDoesNotExistException, LineageDeletionException {
    Lineage lineage = mLineageStore.getLineage(lineageId);
    LineageDoesNotExistException.check(lineage != null, ExceptionMessage.LINEAGE_DOES_NOT_EXIST,
        lineageId);

    // there should not be child lineage if not cascade
    if (!cascade && !mLineageStore.getChildren(lineage).isEmpty()) {
      throw new LineageDeletionException(
          ExceptionMessage.DELETE_LINEAGE_WITH_CHILDREN.getMessage(lineageId));
    }

    LOG.info("Delete lineage {}", lineageId);
    mLineageStore.deleteLineage(lineageId);
    return true;
  }

  private void deleteLineageFromEntry(DeleteLineageEntry entry) {
    try {
      deleteLineageInternal(entry.getLineageId(), entry.getCascade());
    } catch (LineageDoesNotExistException e) {
      LOG.error("Failed to delete lineage {}", entry.getLineageId(), e);
    } catch (LineageDeletionException e) {
      LOG.error("Failed to delete lineage {}", entry.getLineageId(), e);
    }
  }

  /**
   * Reinitializes the file when the file is lost or not completed.
   *
   * @param path the path to the file
   * @param blockSizeBytes the block size
   * @param ttl the TTL
   * @return the id of the reinitialized file when the file is lost or not completed, -1 otherwise
   * @throws InvalidPathException the file path is invalid
   */
  public synchronized long reinitializeFile(String path, long blockSizeBytes, long ttl)
      throws InvalidPathException, LineageDoesNotExistException {
    long fileId = mFileSystemMaster.getFileId(new TachyonURI(path));
    LineageFileState state = mLineageStore.getLineageFileState(fileId);
    if (state == LineageFileState.CREATED || state == LineageFileState.LOST) {
      LOG.info("Recreate the file {} with block size of {} bytes", path, blockSizeBytes);
      return mFileSystemMaster.reinitializeFile(new TachyonURI(path), blockSizeBytes, ttl);
    }
    return -1;
  }

  /**
   * Completes an output file in Tachyon.
   *
   * @param fileId id of the file
   * @throws FileDoesNotExistException if the file does not exist
   * @throws BlockInfoException if the completion fails
   */
  public synchronized void asyncCompleteFile(long fileId)
      throws FileDoesNotExistException, BlockInfoException, InvalidFileSizeException,
      FileAlreadyCompletedException {
    LOG.info("Async complete file {}", fileId);
    // complete file in Tachyon.
    try {
      mFileSystemMaster.completeFile(fileId, CompleteFileOptions.defaults());
    } catch (InvalidPathException e) {
      // should not happen
      throw new RuntimeException(e);
    }
    mLineageStore.completeFile(fileId);
    AsyncCompleteFileEntry asyncCompleteFile = AsyncCompleteFileEntry.newBuilder()
        .setFileId(fileId)
        .build();
    writeJournalEntry(JournalEntry.newBuilder().setAsyncCompleteFile(asyncCompleteFile).build());
    flushJournal();
  }

  private void asyncCompleteFileFromEntry(AsyncCompleteFileEntry entry) {
    mLineageStore.completeFile(entry.getFileId());
  }

  /**
   * Instructs a worker to persist the files for checkpoint.
   *
   * @param workerId the id of the worker that heartbeats
   * @return the command for checkpointing the blocks of a file
   * @throws FileDoesNotExistException if the file does not exist
   * @throws InvalidPathException if the file path is invalid
   * @throws LineageDoesNotExistException if the lineage does not exist
   */
  public synchronized LineageCommand lineageWorkerHeartbeat(long workerId,
      List<Long> persistedFiles)
          throws FileDoesNotExistException, InvalidPathException, LineageDoesNotExistException {
    if (!persistedFiles.isEmpty()) {
      // notify checkpoint manager the persisted files
      persistFiles(workerId, persistedFiles);
    }

    // get the files for the given worker to checkpoint
    List<CheckpointFile> filesToCheckpoint = null;
    filesToCheckpoint = pollToCheckpoint(workerId);
    if (!filesToCheckpoint.isEmpty()) {
      LOG.info("Sent files {} to worker {} to persist", filesToCheckpoint, workerId);
    }
    return new LineageCommand(CommandType.Persist, filesToCheckpoint);
  }

  /**
   * @return the list of all the {@link LineageInfo}s
   * @throws LineageDoesNotExistException if the lineage does not exist
   */
  public synchronized List<LineageInfo> getLineageInfoList() throws LineageDoesNotExistException {
    List<LineageInfo> lineages = Lists.newArrayList();

    for (Lineage lineage : mLineageStore.getAllInTopologicalOrder()) {
      LineageInfo info = lineage.generateLineageInfo();
      List<Long> parents = Lists.newArrayList();
      for (Lineage parent : mLineageStore.getParents(lineage)) {
        parents.add(parent.getId());
      }
      info.parents = parents;
      List<Long> children = Lists.newArrayList();
      for (Lineage child : mLineageStore.getChildren(lineage)) {
        children.add(child.getId());
      }
      info.children = children;
      lineages.add(info);
    }
    return lineages;
  }

  /**
   * It takes a checkpoint plan and queues for the lineage checkpointing service to checkpoint the
   * lineages in the plan.
   *
   * @param plan the plan for checkpointing
   */
  public synchronized void queueForCheckpoint(CheckpointPlan plan) {
    for (long lineageId : plan.getLineagesToCheckpoint()) {
      Lineage lineage = mLineageStore.getLineage(lineageId);
      // register the lineage file to checkpoint
      for (LineageFile file : lineage.getOutputFiles()) {
        // find the worker
        long workerId = getWorkerStoringFile(file);
        if (workerId == -1) {
          // the file is not on any worker
          continue;
        }
        if (!mWorkerToCheckpointFile.containsKey(workerId)) {
          mWorkerToCheckpointFile.put(workerId, Sets.<LineageFile>newHashSet());
        }
        mWorkerToCheckpointFile.get(workerId).add(file);
      }
    }
  }

  /**
   * Polls the files to send to the given worker for checkpoint
   *
   * @param workerId the worker id
   * @return the list of files
   * @throws FileDoesNotExistException if the file does not exist
   * @throws InvalidPathException if the path is invalid
   * @throws LineageDoesNotExistException if the lineage does not exist
   */
  private synchronized List<CheckpointFile> pollToCheckpoint(long workerId)
      throws FileDoesNotExistException, InvalidPathException, LineageDoesNotExistException {
    List<CheckpointFile> files = Lists.newArrayList();
    if (!mWorkerToCheckpointFile.containsKey(workerId)) {
      return files;
    }

    List<Long> toRequestFilePersistence = Lists.newArrayList();
    for (LineageFile file : mWorkerToCheckpointFile.get(workerId)) {
      if (file.getState() == LineageFileState.COMPLETED) {
        long fileId = file.getFileId();
        toRequestFilePersistence.add(fileId);
        List<Long> blockIds = Lists.newArrayList();
        for (FileBlockInfo fileBlockInfo : mFileSystemMaster.getFileBlockInfoList(fileId)) {
          blockIds.add(fileBlockInfo.blockInfo.blockId);
        }

        CheckpointFile toCheckpoint = new CheckpointFile(fileId, blockIds);
        files.add(toCheckpoint);
      }
    }

    requestFilePersistence(toRequestFilePersistence);
    return files;
  }

  public synchronized void reportListFile(String path) throws FileDoesNotExistException,
      IOException {
    long fileId = mFileSystemMaster.getFileId(new TachyonURI(path));
    mFileSystemMaster.reportLostFile(fileId);
  }

  /**
   * Request a list of files as being persisted
   *
   * @param fileIds the id of the files
   * @throws LineageDoesNotExistException if the lineage does not exist
   */
  public synchronized void requestFilePersistence(List<Long> fileIds)
      throws LineageDoesNotExistException {
    if (!fileIds.isEmpty()) {
      LOG.info("Request file persistency: {}", fileIds);
    }
    for (long fileId : fileIds) {
      mLineageStore.requestFilePersistence(fileId);
    }
    PersistFilesRequestEntry persistFilesRequest = PersistFilesRequestEntry.newBuilder()
        .addAllFileIds(fileIds)
        .build();
    writeJournalEntry(
        JournalEntry.newBuilder().setPersistFilesRequest(persistFilesRequest).build());
    flushJournal();
  }

  private synchronized void requestFilePersistenceFromEntry(PersistFilesRequestEntry entry)
      throws IOException {
    for (long fileId : entry.getFileIdsList()) {
      try {
        mLineageStore.requestFilePersistence(fileId);
      } catch (LineageDoesNotExistException e) {
        throw new IOException(e.getMessage());
      }
    }
  }

  /**
   * Commits the given list of files as persisted in under file system on a worker.
   *
   * @param workerId the worker id
   * @param persistedFiles the persisted files
   * @throws LineageDoesNotExistException if the lineage does not exist
   */
  private synchronized void persistFiles(long workerId, List<Long> persistedFiles)
      throws LineageDoesNotExistException {
    Preconditions.checkNotNull(persistedFiles);

    if (!persistedFiles.isEmpty()) {
      LOG.info("Files persisted on worker {}:{}", workerId, persistedFiles);
    }
    for (Long fileId : persistedFiles) {
      mLineageStore.commitFilePersistence(fileId);
    }
    PersistFilesEntry persistFiles = PersistFilesEntry.newBuilder()
        .addAllFileIds(persistedFiles)
        .build();
    writeJournalEntry(JournalEntry.newBuilder().setPersistFiles(persistFiles).build());
    flushJournal();
  }

  private synchronized void persistFilesFromEntry(PersistFilesEntry entry)
      throws IOException {
    for (Long fileId : entry.getFileIdsList()) {
      try {
        mLineageStore.commitFilePersistence(fileId);
      } catch (LineageDoesNotExistException e) {
        throw new IOException(e.getMessage());
      }
    }
  }

  private long getWorkerStoringFile(LineageFile file) {
    List<Long> workers = Lists.newArrayList();
    try {
      for (FileBlockInfo fileBlockInfo : mFileSystemMaster.getFileBlockInfoList(file.getFileId())) {
        for (BlockLocation blockLocation : fileBlockInfo.blockInfo.locations) {
          workers.add(blockLocation.workerId);
        }
      }
    } catch (FileDoesNotExistException e) {
      // should not happen
      throw new RuntimeException(e);
    } catch (InvalidPathException e) {
      // should not happen
      throw new RuntimeException(e);
    }

    if (workers.size() == 0) {
      LOG.info("the file {} is not on any worker", file);
      return -1;
    }
    Preconditions.checkState(workers.size() < 2,
        "the file is stored at more than one worker: " + workers);
    return workers.get(0);
  }
}
