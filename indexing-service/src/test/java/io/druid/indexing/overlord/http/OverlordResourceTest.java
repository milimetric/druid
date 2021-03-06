/*
* Licensed to Metamarkets Group Inc. (Metamarkets) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. Metamarkets licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package io.druid.indexing.overlord.http;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.metamx.common.concurrent.ScheduledExecutorFactory;
import com.metamx.common.guava.CloseQuietly;
import com.metamx.emitter.EmittingLogger;
import com.metamx.emitter.service.ServiceEmitter;
import io.druid.concurrent.Execs;
import io.druid.curator.PotentiallyGzippedCompressionProvider;
import io.druid.curator.discovery.NoopServiceAnnouncer;
import io.druid.indexing.common.TaskStatus;
import io.druid.indexing.common.actions.TaskActionClientFactory;
import io.druid.indexing.common.config.TaskStorageConfig;
import io.druid.indexing.common.task.NoopTask;
import io.druid.indexing.common.task.Task;
import io.druid.indexing.overlord.HeapMemoryTaskStorage;
import io.druid.indexing.overlord.RemoteTaskRunner;
import io.druid.indexing.overlord.TaskLockbox;
import io.druid.indexing.overlord.TaskMaster;
import io.druid.indexing.overlord.TaskRunner;
import io.druid.indexing.overlord.TaskRunnerFactory;
import io.druid.indexing.overlord.TaskRunnerWorkItem;
import io.druid.indexing.overlord.TaskStorage;
import io.druid.indexing.overlord.TaskStorageQueryAdapter;
import io.druid.indexing.overlord.ZkWorker;
import io.druid.indexing.overlord.autoscaling.NoopResourceManagementScheduler;
import io.druid.indexing.overlord.autoscaling.ResourceManagementScheduler;
import io.druid.indexing.overlord.autoscaling.ResourceManagementSchedulerFactory;
import io.druid.indexing.overlord.config.TaskQueueConfig;
import io.druid.server.DruidNode;
import io.druid.server.initialization.IndexerZkConfig;
import io.druid.server.initialization.ZkPathsConfig;
import io.druid.server.metrics.NoopServiceEmitter;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.Timing;
import org.easymock.EasyMock;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.core.Response;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

public class OverlordResourceTest
{
  private TestingServer server;
  private Timing timing;
  private CuratorFramework curator;
  private TaskMaster taskMaster;
  private TaskLockbox taskLockbox;
  private TaskStorage taskStorage;
  private TaskActionClientFactory taskActionClientFactory;
  private CountDownLatch announcementLatch;
  private DruidNode druidNode;
  private OverlordResource overlordResource;
  private CountDownLatch[] taskCountDownLatches;

  private void setupServerAndCurator() throws Exception
  {
    server = new TestingServer();
    timing = new Timing();
    curator = CuratorFrameworkFactory
        .builder()
        .connectString(server.getConnectString())
        .sessionTimeoutMs(timing.session())
        .connectionTimeoutMs(timing.connection())
        .retryPolicy(new RetryOneTime(1))
        .compressionProvider(new PotentiallyGzippedCompressionProvider(true))
        .build();
  }

  private void tearDownServerAndCurator()
  {
    CloseQuietly.close(curator);
    CloseQuietly.close(server);
  }

  @Before
  public void setUp() throws Exception
  {
    taskLockbox = EasyMock.createStrictMock(TaskLockbox.class);
    taskLockbox.syncFromStorage();
    EasyMock.expectLastCall().atLeastOnce();
    taskLockbox.unlock(EasyMock.<Task>anyObject());
    EasyMock.expectLastCall().atLeastOnce();
    taskActionClientFactory = EasyMock.createStrictMock(TaskActionClientFactory.class);
    EasyMock.expect(taskActionClientFactory.create(EasyMock.<Task>anyObject()))
            .andReturn(null).anyTimes();
    EasyMock.replay(taskLockbox, taskActionClientFactory);

    taskStorage = new HeapMemoryTaskStorage(new TaskStorageConfig(null));
    taskCountDownLatches = new CountDownLatch[2];
    taskCountDownLatches[0] = new CountDownLatch(1);
    taskCountDownLatches[1] = new CountDownLatch(1);
    announcementLatch = new CountDownLatch(1);
    IndexerZkConfig indexerZkConfig = new IndexerZkConfig(new ZkPathsConfig(), null, null, null, null, null);
    setupServerAndCurator();
    curator.start();
    curator.create().creatingParentsIfNeeded().forPath(indexerZkConfig.getLeaderLatchPath());
    druidNode = new DruidNode("hey", "what", 1234);
    ServiceEmitter serviceEmitter = new NoopServiceEmitter();
    taskMaster = new TaskMaster(
        new TaskQueueConfig(null, new Period(1), null, new Period(10)),
        taskLockbox,
        taskStorage,
        taskActionClientFactory,
        druidNode,
        indexerZkConfig,
        new TaskRunnerFactory()
        {
          @Override
          public TaskRunner build()
          {
            return new MockTaskRunner(taskCountDownLatches);
          }
        },
        new ResourceManagementSchedulerFactory()
        {
          @Override
          public ResourceManagementScheduler build(
              RemoteTaskRunner runner, ScheduledExecutorFactory executorFactory
          )
          {
            return new NoopResourceManagementScheduler();
          }
        },
        curator,
        new NoopServiceAnnouncer()
        {
          @Override
          public void announce(DruidNode node)
          {
            announcementLatch.countDown();
          }
        },
        serviceEmitter
    );
    EmittingLogger.registerEmitter(serviceEmitter);
  }

  @Test(timeout = 2000L)
  public void testOverlordResource() throws Exception
  {
    // basic task master lifecycle test
    taskMaster.start();
    announcementLatch.await();
    while(!taskMaster.isLeading()){
      // I believe the control will never reach here and thread will never sleep but just to be on safe side
      Thread.sleep(10);
    }
    Assert.assertEquals(taskMaster.getLeader(), druidNode.getHostAndPort());
    // Test Overlord resource stuff
    overlordResource = new OverlordResource(taskMaster, new TaskStorageQueryAdapter(taskStorage), null, null, null);
    Response response = overlordResource.getLeader();
    Assert.assertEquals(druidNode.getHostAndPort(), response.getEntity());

    String taskId_0 = "0";
    NoopTask task_0 = new NoopTask(taskId_0, 0, 0, null, null);
    response = overlordResource.taskPost(task_0);
    Assert.assertEquals(200, response.getStatus());
    Assert.assertEquals(ImmutableMap.of("task", taskId_0), response.getEntity());

    // Duplicate task - should fail
    response = overlordResource.taskPost(task_0);
    Assert.assertEquals(400, response.getStatus());

    // Task payload for task_0 should be present in taskStorage
    response = overlordResource.getTaskPayload(taskId_0);
    Assert.assertEquals(task_0, ((Map) response.getEntity()).get("payload"));

    // Task not present in taskStorage - should fail
    response = overlordResource.getTaskPayload("whatever");
    Assert.assertEquals(404, response.getStatus());

    // Task status of the submitted task should be running
    response = overlordResource.getTaskStatus(taskId_0);
    Assert.assertEquals(taskId_0, ((Map) response.getEntity()).get("task"));
    Assert.assertEquals(
        TaskStatus.running(taskId_0).getStatusCode(),
        ((TaskStatus) ((Map) response.getEntity()).get("status")).getStatusCode()
    );

    // Simulate completion of task_0
    taskCountDownLatches[Integer.parseInt(taskId_0)].countDown();
    // Wait for taskQueue to handle success status of task_0
    waitForTaskStatus(taskId_0, TaskStatus.Status.SUCCESS);

    // Manually insert task in taskStorage
    String taskId_1 = "1";
    NoopTask task_1 = new NoopTask(taskId_1, 0, 0, null, null);
    taskStorage.insert(task_1, TaskStatus.running(taskId_1));

    response = overlordResource.getWaitingTasks();
    // 1 task that was manually inserted should be in waiting state
    Assert.assertEquals(1, (((List) response.getEntity()).size()));

    // Simulate completion of task_1
    taskCountDownLatches[Integer.parseInt(taskId_1)].countDown();
    // Wait for taskQueue to handle success status of task_1
    waitForTaskStatus(taskId_1, TaskStatus.Status.SUCCESS);

    // should return number of tasks which are not in running state
    response = overlordResource.getCompleteTasks();
    Assert.assertEquals(2, (((List) response.getEntity()).size()));
    taskMaster.stop();
    Assert.assertFalse(taskMaster.isLeading());
    EasyMock.verify(taskLockbox, taskActionClientFactory);
  }

  /* Wait until the task with given taskId has the given Task Status
   * These method will not timeout until the condition is met so calling method should ensure timeout
   * This method also assumes that the task with given taskId is present
   * */
  private void waitForTaskStatus(String taskId, TaskStatus.Status status) throws InterruptedException {
    while (true) {
      Response response = overlordResource.getTaskStatus(taskId);
      if (status.equals(((TaskStatus) ((Map) response.getEntity()).get("status")).getStatusCode())) {
        break;
      }
      Thread.sleep(10);
    }
  }

  @After
  public void tearDown() throws Exception
  {
    tearDownServerAndCurator();
  }

  public static class MockTaskRunner implements TaskRunner
  {
    private CountDownLatch[] taskLatches;
    private Map<Integer, TaskRunnerWorkItem> taskRunnerWorkItems;
    private Map<Integer, TaskRunnerWorkItem> runningTaskRunnerWorkItems;

    public MockTaskRunner(CountDownLatch[] taskLatches)
    {
      this.taskLatches = taskLatches;
      this.taskRunnerWorkItems = new HashMap<>();
      this.runningTaskRunnerWorkItems = new HashMap<>();
    }

    @Override
    public ListenableFuture<TaskStatus> run(final Task task)
    {
      final int taskId = Integer.parseInt(task.getId());
      ListenableFuture<TaskStatus> future = MoreExecutors.listeningDecorator(
          Execs.singleThreaded(
              "noop_test_task_exec_%s"
          )
      ).submit(
          new Callable<TaskStatus>()
          {
            @Override
            public TaskStatus call() throws Exception
            {
              try {
                taskLatches[taskId].await();
              }
              catch (InterruptedException e) {
                throw new RuntimeException("Thread was interrupted!");
              }
              runningTaskRunnerWorkItems.remove(taskId);
              return TaskStatus.success(task.getId());
            }
          }
      );
      TaskRunnerWorkItem taskRunnerWorkItem = new TaskRunnerWorkItem(task.getId(), future);
      runningTaskRunnerWorkItems.put(taskId, taskRunnerWorkItem);
      taskRunnerWorkItems.put(taskId, taskRunnerWorkItem);
      return future;
    }

    @Override
    public void shutdown(String taskid) {}

    @Override
    public Collection<? extends TaskRunnerWorkItem> getRunningTasks()
    {
      return runningTaskRunnerWorkItems.values();
    }

    @Override
    public Collection<? extends TaskRunnerWorkItem> getPendingTasks()
    {
      return ImmutableList.of();
    }

    @Override
    public Collection<? extends TaskRunnerWorkItem> getKnownTasks()
    {
      return taskRunnerWorkItems.values();
    }

    @Override
    public Collection<ZkWorker> getWorkers()
    {
      return ImmutableList.of();
    }
  }
}
