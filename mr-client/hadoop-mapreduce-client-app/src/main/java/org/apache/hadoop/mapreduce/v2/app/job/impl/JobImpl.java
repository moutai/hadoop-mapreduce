/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapreduce.v2.app.job.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.JobACL;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.jobhistory.JobFinishedEvent;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.jobhistory.JobInitedEvent;
import org.apache.hadoop.mapreduce.jobhistory.JobSubmittedEvent;
import org.apache.hadoop.mapreduce.jobhistory.JobUnsuccessfulCompletionEvent;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.apache.hadoop.mapreduce.split.SplitMetaInfoReader;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitMetaInfo;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.hadoop.mapreduce.v2.api.records.Counter;
import org.apache.hadoop.mapreduce.v2.api.records.CounterGroup;
import org.apache.hadoop.mapreduce.v2.api.records.Counters;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.JobReport;
import org.apache.hadoop.mapreduce.v2.api.records.JobState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptCompletionEvent;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptCompletionEventStatus;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.TaskAttemptListener;
import org.apache.hadoop.mapreduce.v2.app.job.Task;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobFinishEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobTaskAttemptCompletedEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobTaskAttemptFetchFailureEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobTaskEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEventType;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YARNApplicationConstants;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;
import org.apache.hadoop.yarn.state.MultipleArcTransition;
import org.apache.hadoop.yarn.state.SingleArcTransition;
import org.apache.hadoop.yarn.state.StateMachine;
import org.apache.hadoop.yarn.state.StateMachineFactory;

/** Implementation of Job interface. Maintains the state machines of Job.
 * The read and write calls use ReadWriteLock for concurrency.
 */
public class JobImpl implements org.apache.hadoop.mapreduce.v2.app.job.Job, 
  EventHandler<JobEvent> {

  private static final Log LOG = LogFactory.getLog(JobImpl.class);
  public static final 
    float DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART = 0.05f;

  //The maximum percentage of fetch failures allowed for a map 
  private static final double MAX_ALLOWED_FETCH_FAILURES_PERCENT = 0.5;

  // Maximum no. of fetch-failure notifications after which map task is failed
  private static final int MAX_FETCH_FAILURES_NOTIFICATIONS = 3;

  private final RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);
  
  //final fields
  private final Lock readLock;
  private final Lock writeLock;
  private final JobId jobId;
  private final org.apache.hadoop.mapreduce.JobID oldJobId;
  private final TaskAttemptListener taskAttemptListener;
  private boolean lazyTasksCopyNeeded = false;
  private final Object tasksSyncHandle = new Object();
  private volatile Map<TaskId, Task> tasks = new LinkedHashMap<TaskId, Task>();
  private final Set<TaskId> mapTasks = new LinkedHashSet<TaskId>();
  private final Set<TaskId> reduceTasks = new LinkedHashSet<TaskId>();
  private final EventHandler eventHandler;
  public Configuration conf;
  
  //fields initialized in init
  private FileSystem fs;
  private Path remoteJobSubmitDir;
  public Path remoteJobConfFile;
  private JobContext jobContext;
  private OutputCommitter committer;
  private int completedMapsForReduceSlowstart;
  private int allowedMapFailuresPercent = 0;
  private int allowedReduceFailuresPercent = 0;
  private List<TaskAttemptCompletionEvent> taskAttemptCompletionEvents;
  private final List<String> diagnostics = new ArrayList<String>();
  
  //task/attempt related datastructures
  private final Map<TaskId, Integer> successAttemptCompletionEventNoMap = 
    new HashMap<TaskId, Integer>();
  private final Map<TaskAttemptId, Integer> fetchFailuresMapping = 
    new HashMap<TaskAttemptId, Integer>();

  private static final TaskAttemptCompletedEventTransition TASK_ATTEMPT_COMPLETED_EVENT_TRANSITION =
      new TaskAttemptCompletedEventTransition();
  private static final DiagnosticsUpdateTransition DIAGNOSTIC_UPDATE_TRANSITION =
    new DiagnosticsUpdateTransition();
  private static final InternalErrorTransition INTERNAL_ERROR_TRANSITION =
      new InternalErrorTransition();
  protected static final
    StateMachineFactory<JobImpl, JobState, JobEventType, JobEvent> 
       stateMachineFactory
     = new StateMachineFactory<JobImpl, JobState, JobEventType, JobEvent>
              (JobState.NEW)

          // Transitions from NEW state
          .addTransition(JobState.NEW, JobState.NEW,
              JobEventType.JOB_DIAGNOSTIC_UPDATE,
              DIAGNOSTIC_UPDATE_TRANSITION)
          .addTransition
             (JobState.NEW,
              EnumSet.of(JobState.RUNNING, JobState.FAILED),
              JobEventType.JOB_INIT,
              new InitTransition())
          .addTransition(JobState.NEW, JobState.KILLED,
                         JobEventType.JOB_KILL, new KillNewJobTransition())
          .addTransition(
              JobState.NEW,
              JobState.ERROR, JobEventType.INTERNAL_ERROR, 
                INTERNAL_ERROR_TRANSITION)

          // Transitions from RUNNING state
          .addTransition(JobState.RUNNING, JobState.RUNNING,
             JobEventType.JOB_TASK_ATTEMPT_COMPLETED_EVENT, 
             TASK_ATTEMPT_COMPLETED_EVENT_TRANSITION)
          .addTransition
             (JobState.RUNNING,
              EnumSet.of(JobState.RUNNING, JobState.SUCCEEDED, JobState.FAILED),
              JobEventType.JOB_TASK_COMPLETED,
              new TaskCompletedTransition())
          .addTransition(JobState.RUNNING, JobState.KILL_WAIT,
                         JobEventType.JOB_KILL, new KillTasksTransition())
          .addTransition(JobState.RUNNING, JobState.RUNNING,
                         JobEventType.JOB_MAP_TASK_RESCHEDULED, 
                         new MapTaskRescheduledTransition())
          .addTransition(JobState.RUNNING, JobState.RUNNING,
              JobEventType.JOB_DIAGNOSTIC_UPDATE,
              DIAGNOSTIC_UPDATE_TRANSITION)
          .addTransition(JobState.RUNNING, JobState.RUNNING,
              JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE,
              new TaskAttemptFetchFailureTransition())
          .addTransition(
              JobState.RUNNING,
              JobState.ERROR, JobEventType.INTERNAL_ERROR, 
              INTERNAL_ERROR_TRANSITION)

          // Transitions from KILL_WAIT state.
          .addTransition
             (JobState.KILL_WAIT,
              EnumSet.of(JobState.KILL_WAIT, JobState.KILLED),
              JobEventType.JOB_TASK_COMPLETED,
              new KillWaitTaskCompletedTransition())
          .addTransition(JobState.KILL_WAIT, JobState.KILL_WAIT,
              JobEventType.JOB_TASK_ATTEMPT_COMPLETED_EVENT,
              TASK_ATTEMPT_COMPLETED_EVENT_TRANSITION)
          .addTransition(JobState.KILL_WAIT, JobState.KILL_WAIT,
              JobEventType.JOB_DIAGNOSTIC_UPDATE,
              DIAGNOSTIC_UPDATE_TRANSITION)
          .addTransition(
              JobState.KILL_WAIT,
              JobState.ERROR, JobEventType.INTERNAL_ERROR, 
              INTERNAL_ERROR_TRANSITION)
          // Ignore-able events
          .addTransition(JobState.KILL_WAIT, JobState.KILL_WAIT,
              EnumSet.of(JobEventType.JOB_KILL,
                         JobEventType.JOB_MAP_TASK_RESCHEDULED,
                         JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE))

          // Transitions from SUCCEEDED state
          .addTransition(JobState.SUCCEEDED, JobState.SUCCEEDED,
              JobEventType.JOB_DIAGNOSTIC_UPDATE,
              DIAGNOSTIC_UPDATE_TRANSITION)
          .addTransition(
              JobState.SUCCEEDED,
              JobState.ERROR, JobEventType.INTERNAL_ERROR, 
              INTERNAL_ERROR_TRANSITION)
          // Ignore-able events
          .addTransition(JobState.SUCCEEDED, JobState.SUCCEEDED,
              EnumSet.of(JobEventType.JOB_KILL,
                  JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE))

          // Transitions from FAILED state
          .addTransition(JobState.FAILED, JobState.FAILED,
              JobEventType.JOB_DIAGNOSTIC_UPDATE,
              DIAGNOSTIC_UPDATE_TRANSITION)
          .addTransition(
              JobState.FAILED,
              JobState.ERROR, JobEventType.INTERNAL_ERROR, 
              INTERNAL_ERROR_TRANSITION)
          // Ignore-able events
          .addTransition(JobState.FAILED, JobState.FAILED,
              EnumSet.of(JobEventType.JOB_KILL,
                  JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE))

          // Transitions from KILLED state
          .addTransition(JobState.KILLED, JobState.KILLED,
              JobEventType.JOB_DIAGNOSTIC_UPDATE,
              DIAGNOSTIC_UPDATE_TRANSITION)
          .addTransition(
              JobState.KILLED,
              JobState.ERROR, JobEventType.INTERNAL_ERROR, 
              INTERNAL_ERROR_TRANSITION)
          // Ignore-able events
          .addTransition(JobState.KILLED, JobState.KILLED,
              EnumSet.of(JobEventType.JOB_KILL,
                  JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE))

          // No transitions from INTERNAL_ERROR state. Ignore all.
          .addTransition(
              JobState.ERROR,
              JobState.ERROR,
              EnumSet.of(JobEventType.JOB_INIT,
                  JobEventType.JOB_KILL,
                  JobEventType.JOB_TASK_COMPLETED,
                  JobEventType.JOB_TASK_ATTEMPT_COMPLETED_EVENT,
                  JobEventType.JOB_MAP_TASK_RESCHEDULED,
                  JobEventType.JOB_DIAGNOSTIC_UPDATE,
                  JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE,
                  JobEventType.INTERNAL_ERROR))

          // create the topology tables
          .installTopology();
 
  private final StateMachine<JobState, JobEventType, JobEvent> stateMachine;

  //changing fields while the job is running
  private int numMapTasks;
  private int numReduceTasks;
  private int completedTaskCount = 0;
  private int succeededMapTaskCount = 0;
  private int succeededReduceTaskCount = 0;
  private int failedMapTaskCount = 0;
  private int failedReduceTaskCount = 0;
  private int killedMapTaskCount = 0;
  private int killedReduceTaskCount = 0;
  private long startTime;
  private long finishTime;
  private float setupProgress;
  private float cleanupProgress;
  private boolean reducesScheduled;

  private Credentials fsTokens;
  private Token<JobTokenIdentifier> jobToken;
  private JobTokenSecretManager jobTokenSecretManager;

  public JobImpl(ApplicationId appID, Configuration conf,
      EventHandler eventHandler, TaskAttemptListener taskAttemptListener,
      JobTokenSecretManager jobTokenSecretManager,
      Credentials fsTokenCredentials) {

    this.jobId = recordFactory.newRecordInstance(JobId.class);
    this.conf = conf;
    jobId.setAppId(appID);
    jobId.setId(appID.getId());
    oldJobId = TypeConverter.fromYarn(jobId);
    LOG.info("Job created" +
    		" appId=" + appID + 
    		" jobId=" + jobId + 
    		" oldJobId=" + oldJobId);
    
    this.taskAttemptListener = taskAttemptListener;
    this.eventHandler = eventHandler;
    ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    this.readLock = readWriteLock.readLock();
    this.writeLock = readWriteLock.writeLock();

    this.fsTokens = fsTokenCredentials;
    this.jobTokenSecretManager = jobTokenSecretManager;

    // This "this leak" is okay because the retained pointer is in an
    //  instance variable.
    stateMachine = stateMachineFactory.make(this);
  }

  protected StateMachine<JobState, JobEventType, JobEvent> getStateMachine() {
    return stateMachine;
  }

  @Override
  public JobId getID() {
    return jobId;
  }

  @Override
  public Task getTask(TaskId taskID) {
    readLock.lock();
    try {
      return tasks.get(taskID);
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public int getCompletedMaps() {
    readLock.lock();
    try {
      return succeededMapTaskCount + failedMapTaskCount + killedMapTaskCount;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public int getCompletedReduces() {
    readLock.lock();
    try {
      return succeededReduceTaskCount + failedReduceTaskCount 
                  + killedReduceTaskCount;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Counters getCounters() {
    Counters counters = newCounters();
    // TODO: compute job level counters
    readLock.lock();
    try {
      return incrTaskCounters(counters, tasks.values());
    } finally {
      readLock.unlock();
    }
  }

  public static Counters newCounters() {
    Counters counters = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Counters.class);
//    counters.groups = new HashMap<String, CounterGroup>();
    return counters;
  }

  public static Counters incrTaskCounters(Counters counters,
                                          Collection<Task> tasks) {
    for (Task task : tasks) {
      incrAllCounters(counters, task.getCounters());
    }
    return counters;
  }

  public static void incrAllCounters(Counters counters, Counters other) {
    if (other != null) {
      for (CounterGroup otherGroup: other.getAllCounterGroups().values()) {
        CounterGroup group = counters.getCounterGroup(otherGroup.getName());
        if (group == null) {
          group = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(CounterGroup.class);
//          group.counters = new HashMap<CharSequence, Counter>();
          group.setName(otherGroup.getName());
          counters.setCounterGroup(group.getName(), group);
        }
        group.setDisplayName(otherGroup.getDisplayName());
        for (Counter otherCounter : otherGroup.getAllCounters().values()) {
          Counter counter = group.getCounter(otherCounter.getName());
          if (counter == null) {
            counter = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Counter.class);
            counter.setName(otherCounter.getName());
            group.setCounter(counter.getName(), counter);
          }
          counter.setDisplayName(otherCounter.getDisplayName());
          counter.setValue(counter.getValue() + otherCounter.getValue());
        }
      }
    }
  }

  @Override
  public TaskAttemptCompletionEvent[] getTaskAttemptCompletionEvents(
      int fromEventId, int maxEvents) {
    TaskAttemptCompletionEvent[] events = new TaskAttemptCompletionEvent[0];
    readLock.lock();
    try {
      if (taskAttemptCompletionEvents.size() > fromEventId) {
        int actualMax = Math.min(maxEvents,
            (taskAttemptCompletionEvents.size() - fromEventId));
        events = taskAttemptCompletionEvents.subList(fromEventId,
            actualMax + fromEventId).toArray(events);
      }
      return events;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public List<String> getDiagnostics() {
    readLock.lock();
    try {
      return diagnostics;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public JobReport getReport() {
    readLock.lock();
    try {
      JobReport report = recordFactory.newRecordInstance(JobReport.class);
      report.setJobId(jobId);
      report.setJobState(getState());
      
      // TODO - Fix to correctly setup report and to check state
      if (report.getJobState() == JobState.NEW) {
        return report;
      }
      
      report.setStartTime(startTime);
      report.setFinishTime(finishTime);
      report.setSetupProgress(setupProgress);
      report.setCleanupProgress(cleanupProgress);
      report.setMapProgress(computeProgress(mapTasks));
      report.setReduceProgress(computeProgress(reduceTasks));

      return report;
    } finally {
      readLock.unlock();
    }
  }

  private float computeProgress(Set<TaskId> taskIds) {
    readLock.lock();
    try {
      float progress = 0;
      for (TaskId taskId : taskIds) {
        Task task = tasks.get(taskId);
        progress += task.getProgress();
      }
      int taskIdsSize = taskIds.size();
      if (taskIdsSize != 0) {
        progress = progress/taskIdsSize;
      }
      return progress;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Map<TaskId, Task> getTasks() {
    synchronized (tasksSyncHandle) {
      lazyTasksCopyNeeded = true;
      return Collections.unmodifiableMap(tasks);
    }
  }

  @Override
  public Map<TaskId,Task> getTasks(TaskType taskType) {
    Map<TaskId, Task> localTasksCopy = tasks;
    Map<TaskId, Task> result = new HashMap<TaskId, Task>();
    Set<TaskId> tasksOfGivenType = null;
    readLock.lock();
    try {
      if (TaskType.MAP == taskType) {
        tasksOfGivenType = mapTasks;
      } else if (TaskType.REDUCE == taskType) {
        tasksOfGivenType = reduceTasks;
      }
      for (TaskId taskID : tasksOfGivenType)
      result.put(taskID, localTasksCopy.get(taskID));
      return result;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public JobState getState() {
    readLock.lock();
    try {
     return getStateMachine().getCurrentState();
    } finally {
      readLock.unlock();
    }
  }

  protected void scheduleTasks(Set<TaskId> taskIDs) {
    for (TaskId taskID : taskIDs) {
      eventHandler.handle(new TaskEvent(taskID, 
          TaskEventType.T_SCHEDULE));
    }
  }

  @Override
  /**
   * The only entry point to change the Job.
   */
  public void handle(JobEvent event) {
    LOG.info("Processing " + event.getJobId() + " of type " + event.getType());
    try {
      writeLock.lock();
      JobState oldState = getState();
      try {
         getStateMachine().doTransition(event.getType(), event);
      } catch (InvalidStateTransitonException e) {
        LOG.error("Can't handle this event at current state", e);
        addDiagnostic("Invalid event " + event.getType() + 
            " on Job " + this.jobId);
        eventHandler.handle(new JobEvent(this.jobId,
            JobEventType.INTERNAL_ERROR));
      }
      //notify the eventhandler of state change
      if (oldState != getState()) {
        LOG.info(jobId + "Job Transitioned from " + oldState + " to "
                 + getState());
      }
    }
    
    finally {
      writeLock.unlock();
    }
  }

  //helpful in testing
  protected void addTask(Task task) {
    synchronized (tasksSyncHandle) {
      if (lazyTasksCopyNeeded) {
        Map<TaskId, Task> newTasks = new LinkedHashMap<TaskId, Task>();
        newTasks.putAll(tasks);
        tasks = newTasks;
        lazyTasksCopyNeeded = false;
      }
    }
    tasks.put(task.getID(), task);
    if (task.getType() == TaskType.MAP) {
      mapTasks.add(task.getID());
    } else if (task.getType() == TaskType.REDUCE) {
      reduceTasks.add(task.getID());
    }
  }

  private void finished() {
    finishTime = System.currentTimeMillis();
    eventHandler.handle(new JobFinishEvent(jobId));
  }

  @Override
  public String getName() {
    return "FIXME! job name";
  }

  @Override
  public int getTotalMaps() {
    return mapTasks.size();
  }

  @Override
  public int getTotalReduces() {
    return reduceTasks.size();
  }

  public static class InitTransition 
         implements MultipleArcTransition<JobImpl, JobEvent, JobState> {
    @Override
    public JobState transition(JobImpl job, JobEvent event) {
      job.startTime = System.currentTimeMillis();
      try {
        setup(job);
        job.jobContext = new JobContextImpl(job.conf,
            job.oldJobId);
        job.fs = FileSystem.get(job.conf);

        org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId 
            attemptID = RecordFactoryProvider.getRecordFactory(null).newRecordInstance(org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId.class);
        //TODO_get.set
        attemptID.setTaskId(RecordFactoryProvider.getRecordFactory(null).newRecordInstance(TaskId.class));
        attemptID.getTaskId().setJobId(job.jobId);
        attemptID.getTaskId().setTaskType(TaskType.MAP);//TODO:fix task type ??
        
        TaskAttemptContext taskContext =
            new TaskAttemptContextImpl(job.conf,
                TypeConverter.fromYarn(attemptID));
        OutputFormat outputFormat = ReflectionUtils.newInstance(taskContext
            .getOutputFormatClass(), job.conf);
        // TODO: support for old/new committer..
        job.committer = outputFormat.getOutputCommitter(taskContext);

        //log to job history
        JobSubmittedEvent jse =
          new JobSubmittedEvent(job.oldJobId, 
              job.conf.get(MRJobConfig.JOB_NAME, "test"), 
              job.conf.get(MRJobConfig.USER_NAME,"mapred"), job.startTime,
              "test", constructJobACLs(job.conf), 
              job.conf.get(MRJobConfig.QUEUE_NAME,"test"));
        job.eventHandler.handle(new JobHistoryEvent(job.jobId, jse));
        TaskSplitMetaInfo[] taskSplitMetaInfo = createSplits(job,
            job.jobId);
        job.numMapTasks = taskSplitMetaInfo.length;
        job.numReduceTasks = 
          job.conf.getInt(MRJobConfig.NUM_REDUCES, 0);

        checkTaskLimits();

        job.taskAttemptCompletionEvents =
          new ArrayList<TaskAttemptCompletionEvent>(
              job.numMapTasks + job.numReduceTasks
              + 10);

        createMapTasks(job, taskSplitMetaInfo);
        job.allowedMapFailuresPercent = job.conf.getInt(
            MRJobConfig.MAP_FAILURES_MAX_PERCENT, 
            0);

        createReduceTasks(job);
        job.allowedReduceFailuresPercent = job.conf.getInt(
            MRJobConfig.REDUCE_FAILURES_MAXPERCENT, 
            0);

        // Calculate the minimum number of maps to be complete before 
        // we should start scheduling reduces
        job.completedMapsForReduceSlowstart = 
          (int)Math.ceil(
              (job.conf.getFloat(
                  MRJobConfig.COMPLETED_MAPS_FOR_REDUCE_SLOWSTART, 
                        DEFAULT_COMPLETED_MAPS_PERCENT_FOR_REDUCE_SLOWSTART) * 
                        job.numMapTasks));

          //do the setup
        job.committer.setupJob(job.jobContext);
        job.setupProgress = 1.0f;

          //schedule the maps
        job.scheduleTasks(job.mapTasks);
        JobInitedEvent jie =
          new JobInitedEvent(TypeConverter.fromYarn(job.jobId),
               job.startTime,
               job.numMapTasks, job.numReduceTasks,
               false, 0, 0,
               JobState.NEW.toString());
        job.eventHandler.handle(new JobHistoryEvent(job.jobId, jie));

        return JobState.RUNNING;
      } catch (Exception e) {
        LOG.warn("Job init failed", e);
        job.abortJob(org.apache.hadoop.mapreduce.JobStatus.State.FAILED);
        return JobState.FAILED;
      }
    }

    protected void setup(JobImpl job) throws IOException {

      String oldJobIDString = job.oldJobId.toString();
      LOG.info("DEBUG --- startJobs:"
          + " parent="
          + job.conf.get(YARNApplicationConstants.APPS_STAGING_DIR_KEY,
              "/tmp/hadoop-yarn/${user.name}/staging") + " child="
          + oldJobIDString);

      job.remoteJobSubmitDir =
          FileSystem.get(job.conf).makeQualified(
              new Path(job.conf
                  .get(YARNApplicationConstants.APPS_STAGING_DIR_KEY),
                  job.oldJobId.toString()));
      job.remoteJobConfFile =
          new Path(job.remoteJobSubmitDir,
              YARNApplicationConstants.JOB_CONF_FILE);

      // Prepare the TaskAttemptListener server for authentication of Containers
      // TaskAttemptListener gets the information via jobTokenSecretManager.
      JobTokenIdentifier identifier =
          new JobTokenIdentifier(new Text(oldJobIDString));
      job.jobToken =
          new Token<JobTokenIdentifier>(identifier, job.jobTokenSecretManager);
      job.jobToken.setService(identifier.getJobId());
      // Add it to the jobTokenSecretManager so that TaskAttemptListener server
      // can authenticate containers(tasks)
      job.jobTokenSecretManager.addTokenForJob(oldJobIDString, job.jobToken);
      LOG.info("Adding job token for " + oldJobIDString
          + " to jobTokenSecretManager");

      // Upload the jobTokens onto the remote FS so that ContainerManager can
      // localize it to be used by the Containers(tasks)
      Credentials tokenStorage = new Credentials();
      TokenCache.setJobToken(job.jobToken, tokenStorage);

      if (UserGroupInformation.isSecurityEnabled()) {
        tokenStorage.addAll(job.fsTokens);
      }

      Path remoteJobTokenFile =
          new Path(job.remoteJobSubmitDir,
              YarnConfiguration.APPLICATION_TOKENS_FILE);
      tokenStorage.writeTokenStorageFile(remoteJobTokenFile, job.conf);
      LOG.info("Written back the job-token file on the remote file system:"
          + remoteJobTokenFile.toString());
    }

    private void createMapTasks(JobImpl job, TaskSplitMetaInfo[] splits) {
      long inputLength = 0;
      for(int i=0; i < job.numMapTasks; ++i) {
        inputLength += splits[i].getInputDataLength();
        TaskImpl task =
            new MapTaskImpl(job.jobId, i,
                job.eventHandler, 
                job.remoteJobConfFile, 
                job.conf, splits[i], 
                job.taskAttemptListener, 
                job.committer, job.jobToken, job.fsTokens.getAllTokens());
        job.addTask(task);
      }
      LOG.info("Input size for job " + job.jobId + " = " + inputLength
          + ". Number of splits = " + splits.length);
    }

    private void createReduceTasks(JobImpl job) {
      for (int i = 0; i < job.numReduceTasks; i++) {
        TaskImpl task =
            new ReduceTaskImpl(job.jobId, i,
                job.eventHandler, 
                job.remoteJobConfFile, 
                job.conf, job.numMapTasks, 
                job.taskAttemptListener, job.committer, job.jobToken,
                job.fsTokens.getAllTokens());
        job.addTask(task);
      }
      LOG.info("Number of reduces for job " + job.
          jobId + " = " + job.numReduceTasks);
    }

    protected TaskSplitMetaInfo[] createSplits(JobImpl job, JobId jobId) {
      TaskSplitMetaInfo[] allTaskSplitMetaInfo;
      try {
        allTaskSplitMetaInfo = SplitMetaInfoReader.readSplitMetaInfo(
            job.oldJobId, job.fs, 
            job.conf, 
            job.remoteJobSubmitDir);
      } catch (IOException e) {
        throw new YarnException(e);
      }
      return allTaskSplitMetaInfo;
    }

    /**
     * If the number of tasks are greater than the configured value
     * throw an exception that will fail job initialization
     */
    private void checkTaskLimits() {
      // no code, for now
    }
  }

  private void abortJob(
      org.apache.hadoop.mapreduce.JobStatus.State finalState) {
    try {
      committer.abortJob(jobContext, finalState);
    } catch (IOException e) {
      LOG.warn("Could not abortJob", e);
    }
    cleanupProgress = 1.0f;
    JobUnsuccessfulCompletionEvent unsuccessfulJobEvent =
      new JobUnsuccessfulCompletionEvent(TypeConverter.fromYarn(jobId),
          finishTime,
          succeededMapTaskCount,
          numReduceTasks, //TODO finishedReduceTasks
          finalState.toString());
    eventHandler.handle(new JobHistoryEvent(jobId, unsuccessfulJobEvent));
  }

  private static class KillNewJobTransition 
    implements SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      JobUnsuccessfulCompletionEvent failedEvent =
          new JobUnsuccessfulCompletionEvent(TypeConverter.fromYarn(job.jobId),
              job.finishTime,
              job.succeededMapTaskCount,
              job.numReduceTasks, //TODO finishedReduceTasks
              org.apache.hadoop.mapreduce.JobStatus.State.FAILED.toString()); //TODO correct state
      job.eventHandler.handle(new JobHistoryEvent(job.jobId, failedEvent));

      job.finished();
    }
  }

  private static class KillTasksTransition 
      implements SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      for (Task task : job.tasks.values()) {
        job.eventHandler.handle(
            new TaskEvent(task.getID(), TaskEventType.T_KILL));
      }
    }
  }

  private static class TaskAttemptCompletedEventTransition implements
      SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      TaskAttemptCompletionEvent tce = 
        ((JobTaskAttemptCompletedEvent) event).getCompletionEvent();
      // Add the TaskAttemptCompletionEvent
      //eventId is equal to index in the arraylist
      tce.setEventId(job.taskAttemptCompletionEvents.size());
      job.taskAttemptCompletionEvents.add(tce);
      
      //make the previous completion event as obsolete if it exists
      Object successEventNo = 
        job.successAttemptCompletionEventNoMap.remove(tce.getAttemptId().getTaskId());
      if (successEventNo != null) {
        TaskAttemptCompletionEvent successEvent = 
          job.taskAttemptCompletionEvents.get((Integer) successEventNo);
        successEvent.setStatus(TaskAttemptCompletionEventStatus.OBSOLETE);
      }

      if (TaskAttemptCompletionEventStatus.SUCCEEDED.equals(tce.getStatus())) {
        job.successAttemptCompletionEventNoMap.put(tce.getAttemptId().getTaskId(), 
            tce.getEventId());
      }
    }
  }

  private static class TaskAttemptFetchFailureTransition implements
      SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      JobTaskAttemptFetchFailureEvent fetchfailureEvent = 
        (JobTaskAttemptFetchFailureEvent) event;
      for (org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId mapId : 
            fetchfailureEvent.getMaps()) {
        Integer fetchFailures = job.fetchFailuresMapping.get(mapId);
        fetchFailures = (fetchFailures == null) ? 1 : (fetchFailures+1);
        job.fetchFailuresMapping.put(mapId, fetchFailures);
        
        //get number of running reduces
        int runningReduceTasks = 0;
        for (TaskId taskId : job.reduceTasks) {
          if (TaskState.RUNNING.equals(job.tasks.get(taskId).getState())) {
            runningReduceTasks++;
          }
        }
        
        float failureRate = (float) fetchFailures / runningReduceTasks;
        // declare faulty if fetch-failures >= max-allowed-failures
        boolean isMapFaulty = (failureRate >= MAX_ALLOWED_FETCH_FAILURES_PERCENT) 
                              ? true
                              : false;
        if (fetchFailures >= MAX_FETCH_FAILURES_NOTIFICATIONS
            && isMapFaulty) {
          LOG.info("Too many fetch-failures for output of task attempt: " + 
              mapId + " ... raising fetch failure to map");
          job.eventHandler.handle(new TaskAttemptEvent(mapId, 
              TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURE));
          job.fetchFailuresMapping.remove(mapId);
        }
      }
    }
  }

  private static class TaskCompletedTransition implements
      MultipleArcTransition<JobImpl, JobEvent, JobState> {

    @Override
    public JobState transition(JobImpl job, JobEvent event) {
      job.completedTaskCount++;
      LOG.info("No of completed Tasks:" + job.completedTaskCount);
      JobTaskEvent taskEvent = (JobTaskEvent) event;
      Task task = job.tasks.get(taskEvent.getTaskID());
      if (taskEvent.getState() == TaskState.SUCCEEDED) {
        taskSucceeded(job, task);
      } else if (taskEvent.getState() == TaskState.FAILED) {
        taskFailed(job, task);
      } else if (taskEvent.getState() == TaskState.KILLED) {
        taskKilled(job, task);
      }

      return checkJobForCompletion(job);
    }

    protected JobState checkJobForCompletion(JobImpl job) {
      //check for Job failure
      if (job.failedMapTaskCount*100 > 
        job.allowedMapFailuresPercent*job.numMapTasks ||
        job.failedReduceTaskCount*100 > 
        job.allowedReduceFailuresPercent*job.numReduceTasks) {
        JobUnsuccessfulCompletionEvent failedEvent =
          new JobUnsuccessfulCompletionEvent(TypeConverter.fromYarn(job.jobId),
              job.finishTime,
              job.failedMapTaskCount,
              job.failedReduceTaskCount, //TODO finishedReduceTasks
              org.apache.hadoop.mapreduce.JobStatus.State.FAILED.toString()); //TODO correct state
        job.eventHandler.handle(new JobHistoryEvent(job.jobId, failedEvent));

// Adding JobFinishedEvent to dump counters
        JobFinishedEvent jfe =
          new JobFinishedEvent(TypeConverter.fromYarn(job.jobId),
              job.finishTime,
              job.succeededMapTaskCount,
              job.succeededReduceTaskCount, job.failedMapTaskCount,
              job.failedReduceTaskCount,
              TypeConverter.fromYarn(job.getCounters()), //TODO replace with MapCounter
              TypeConverter.fromYarn(job.getCounters()), // TODO reduceCounters
              TypeConverter.fromYarn(job.getCounters()));
        job.eventHandler.handle(new JobHistoryEvent(job.jobId, jfe));
        job.abortJob(org.apache.hadoop.mapreduce.JobStatus.State.FAILED);
        job.finished();
        return JobState.FAILED;
      }
      
      //check for Job success
      if (job.completedTaskCount == job.tasks.size()) {
        try {
          job.committer.commitJob(job.jobContext);
        } catch (IOException e) {
          LOG.warn("Could not do commit for Job", e);
        }
       // Log job-history
        JobFinishedEvent jfe =
        new JobFinishedEvent(TypeConverter.fromYarn(job.jobId),
          job.finishTime,
          job.succeededMapTaskCount, job.numReduceTasks, job.failedMapTaskCount,
          job.numReduceTasks, //TODO replace finsihedReduceTasks
          TypeConverter.fromYarn(job.getCounters()), //TODO replace with MapCounter
          TypeConverter.fromYarn(job.getCounters()), // TODO reduceCounters
          TypeConverter.fromYarn(job.getCounters()));
        LOG.info("Calling handler for JobFinishedEvent ");
        job.eventHandler.handle(new JobHistoryEvent(job.jobId, jfe));

        job.finished();
        return JobState.SUCCEEDED;
      }
      
      //return the current state, Job not finished yet
      return job.getState();
    }

    private void taskSucceeded(JobImpl job, Task task) {
      if (task.getType() == TaskType.MAP) {
        job.succeededMapTaskCount++;
        if (!job.reducesScheduled) {
          LOG.info("completedMapsForReduceSlowstart is "
              + job.completedMapsForReduceSlowstart);
          if (job.succeededMapTaskCount == 
            job.completedMapsForReduceSlowstart) {
            // check to see if reduces can be scheduled now
            job.scheduleTasks(job.reduceTasks);
            job.reducesScheduled = true;
          }
        }
      } else {
        job.succeededReduceTaskCount++;
      }
    }
  
    private void taskFailed(JobImpl job, Task task) {
      if (task.getType() == TaskType.MAP) {
        job.failedMapTaskCount++;
      } else if (task.getType() == TaskType.REDUCE) {
        job.failedReduceTaskCount++;
      }
    }

    private void taskKilled(JobImpl job, Task task) {
      if (task.getType() == TaskType.MAP) {
        job.killedMapTaskCount++;
      } else if (task.getType() == TaskType.REDUCE) {
        job.killedReduceTaskCount++;
      }
    }
  }

  private static class MapTaskRescheduledTransition implements
      SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      //succeeded map task is restarted back
      job.completedTaskCount--;
      job.succeededMapTaskCount--;
    }
  }

  private static class KillWaitTaskCompletedTransition extends  
      TaskCompletedTransition {
    @Override
    protected JobState checkJobForCompletion(JobImpl job) {
      if (job.completedTaskCount == job.tasks.size()) {
        job.abortJob(org.apache.hadoop.mapreduce.JobStatus.State.KILLED);
        job.finished();
        return JobState.KILLED;
      }
      //return the current state, Job not finished yet
      return job.getState();
    }
  }

  private void addDiagnostic(String diag) {
    diagnostics.add(diag);
  }

  private static class DiagnosticsUpdateTransition implements
      SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      job.addDiagnostic(((JobDiagnosticsUpdateEvent) event)
          .getDiagnosticUpdate());
    }
  }

  private static class InternalErrorTransition implements
      SingleArcTransition<JobImpl, JobEvent> {
    @Override
    public void transition(JobImpl job, JobEvent event) {
      job.finished();
    }
  }

  private static Map<JobACL, AccessControlList> constructJobACLs(
         Configuration conf) {
       Map<JobACL, AccessControlList> acls =
           new EnumMap<JobACL, AccessControlList>(JobACL.class);
       // Don't construct anything if authorization is disabled.
       if (!conf.getBoolean(MRConfig.MR_ACLS_ENABLED, false)) {
         return acls;
       }
       for (JobACL aclName : JobACL.values()) {
         String aclConfigName = aclName.getAclName();
         String aclConfigured = conf.get(aclConfigName);
         if (aclConfigured == null) {
           // If ACLs are not configured at all, we grant no access to anyone. So
           // jobOwner and superuser/supergroup _only_ can do 'stuff'
           aclConfigured = " ";
         }
         acls.put(aclName, new AccessControlList(aclConfigured));
       }
       return acls;
  }

}
