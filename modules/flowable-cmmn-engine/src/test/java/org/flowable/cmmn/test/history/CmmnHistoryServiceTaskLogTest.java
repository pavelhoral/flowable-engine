/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.cmmn.test.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.engine.CmmnEngineConfiguration;
import org.flowable.cmmn.test.impl.CustomCmmnConfigurationFlowableTestCase;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskLogEntry;
import org.flowable.task.api.TaskLogEntryBuilder;
import org.flowable.task.api.TaskLogEntryQuery;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.Test;

/**
 * @author martin.grofcik
 */
public class CmmnHistoryServiceTaskLogTest extends CustomCmmnConfigurationFlowableTestCase {

    protected Task task;

    @Override
    protected String getEngineName() {
        return "cmmnEngineWithUserTaskEventLogging";
    }

    @Override
    protected void configureConfiguration(CmmnEngineConfiguration cmmnEngineConfiguration) {
        cmmnEngineConfiguration.setEnableUserTaskDatabaseEventLogging(true);
    }

    @After
    public void deleteTasks() {
        if (task != null) {
            deleteTaskWithLogEntries(task.getId());
        }
    }

    protected void deleteTaskWithLogEntries(String taskId) {
        cmmnHistoryService.createTaskLogEntryQuery().taskId(taskId).list().
            forEach(
                logEntry -> cmmnHistoryService.deleteTaskLogEntry(logEntry.getLogNumber())
            );
        cmmnHistoryService.deleteHistoricTaskInstance(taskId);
        cmmnTaskService.deleteTask(taskId);
    }

    @Test
    public void createTaskEvent() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("testAssignee").
            create();

        List<TaskLogEntry> taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
        assertThat(
            taskLogsByTaskInstanceId
        ).size().isEqualTo(1);

        assertThat(taskLogsByTaskInstanceId.get(0)).
            extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogsByTaskInstanceId.get(0)).
            extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_CREATED");
        assertThat(taskLogsByTaskInstanceId.get(0)).
            extracting(TaskLogEntry::getTimeStamp).isEqualTo(task.getCreateTime());
        assertThat(taskLogsByTaskInstanceId.get(0)).
            extracting(TaskLogEntry::getUserId).isNull();
    }

    @Test
    public void createTaskEventAsAuthenticatedUser() {
        String previousUserId = Authentication.getAuthenticatedUserId();
        Authentication.setAuthenticatedUserId("testUser");
        try {
            task = cmmnTaskService.createTaskBuilder().
                assignee("testAssignee").
                create();

            List<TaskLogEntry> taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(
                taskLogsByTaskInstanceId
            ).size().isEqualTo(1);

            assertThat(taskLogsByTaskInstanceId.get(0)).
                extracting(TaskLogEntry::getUserId).isEqualTo("testUser");
        } finally {
            Authentication.setAuthenticatedUserId(previousUserId);
        }
    }

    @Test
    public void queryForNonExistingTaskLogEntries() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        List<TaskLogEntry> taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId("NON-EXISTING-TASK-ID").list();

        assertThat(
            taskLogsByTaskInstanceId
        ).isEmpty();
    }

    @Test
    public void queryForNullTaskLogEntries_returnsAll() {
        Task taskA = cmmnTaskService.createTaskBuilder().
            create();
        Task taskB = cmmnTaskService.createTaskBuilder().
            create();
        Task taskC = cmmnTaskService.createTaskBuilder().
            create();

        try {
            List<TaskLogEntry> taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId(null).list();

            assertThat(
                taskLogsByTaskInstanceId
            ).size().isEqualTo(3L);
        } finally {
            deleteTaskWithLogEntries(taskC.getId());
            deleteTaskWithLogEntries(taskB.getId());
            deleteTaskWithLogEntries(taskA.getId());
        }
    }

    @Test
    public void deleteTaskEventLogEntry() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("testAssignee").
            create();
        List<TaskLogEntry> taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
        assertThat(
            taskLogsByTaskInstanceId
        ).size().isEqualTo(1);

        cmmnHistoryService.deleteTaskLogEntry(taskLogsByTaskInstanceId.get(0).getLogNumber());

        taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
        assertThat(
            taskLogsByTaskInstanceId
        ).isEmpty();
    }

    @Test
    public void deleteNonExistingTaskEventLogEntry() {
        task = cmmnTaskService.createTaskBuilder().
            create();
        // non existing log entry delete should be successful
        cmmnHistoryService.deleteTaskLogEntry(Long.MIN_VALUE);

        assertThat(cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list().size()).isEqualTo(1);
    }

    @Test
    public void taskAssigneeEvent() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("initialAssignee").
            create();

        cmmnTaskService.setAssignee(task.getId(), "newAssignee");
        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        assertThat(taskLogEntries).size().isEqualTo(2);
        assertThat(taskLogEntries.get(1)).
            extracting(assigneeTaskLogEntry -> new String(assigneeTaskLogEntry.getData())).
            isEqualTo("{\"newAssigneeId\":\"newAssignee\",\"previousAssigneeId\":\"initialAssignee\"}");
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTimeStamp).isNotNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getUserId).isNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_ASSIGNEE_CHANGED");
    }

    @Test
    public void changeAssigneeTaskEventAsAuthenticatedUser() {
        assertThatAuthenticatedUserIsSet(
            taskId -> cmmnTaskService.setAssignee(taskId, "newAssignee")
        );
    }

    @Test
    public void taskOwnerEvent() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("initialAssignee").
            create();

        cmmnTaskService.setOwner(task.getId(), "newOwner");
        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        assertThat(taskLogEntries).size().isEqualTo(2);
        assertThat(taskLogEntries.get(1).getData()).
            contains("\"previousOwnerId\":null").
            contains("\"newOwnerId\":\"newOwner\"");
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTimeStamp).isNotNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getUserId).isNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_OWNER_CHANGED");
    }

    @Test
    public void changeOwnerTaskEventAsAuthenticatedUser() {
        assertThatAuthenticatedUserIsSet(
            taskId -> cmmnTaskService.setOwner(taskId, "newOwner")
        );
    }

    @Test
    public void claimTaskEvent() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        cmmnTaskService.claim(task.getId(), "testUser");

        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
        assertThat(taskLogEntries).size().isEqualTo(2);
        assertThat(taskLogEntries.get(1).getData()).
            contains("\"newAssigneeId\":\"testUser\"").
            contains("\"previousAssigneeId\":null");
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTimeStamp).isNotNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getUserId).isNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_ASSIGNEE_CHANGED");
    }

    @Test
    public void unClaimTaskEvent() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("initialAssignee").
            create();

        cmmnTaskService.unclaim(task.getId());

        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
        assertThat(taskLogEntries).size().isEqualTo(2);
        assertThat(taskLogEntries.get(1).getData()).
            contains("\"newAssigneeId\":null").
            contains("\"previousAssigneeId\":\"initialAssignee\"");
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTimeStamp).isNotNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getUserId).isNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_ASSIGNEE_CHANGED");
    }

    @Test
    public void changePriority() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        cmmnTaskService.setPriority(task.getId(), Integer.MAX_VALUE);
        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        assertThat(taskLogEntries).size().isEqualTo(2);
        assertThat(taskLogEntries.get(1).getData()).
            contains("\"newPriority\":2147483647").
            contains("\"previousPriority\":50");
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTimeStamp).isNotNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getUserId).isNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_PRIORITY_CHANGED");
    }

    @Test
    public void changePriorityEventAsAuthenticatedUser() {
        assertThatAuthenticatedUserIsSet(
            taskId ->  cmmnTaskService.setPriority(taskId, Integer.MAX_VALUE)
        );
    }

    protected void assertThatAuthenticatedUserIsSet(Consumer<String> functionToAssert) {
        String previousUserId = Authentication.getAuthenticatedUserId();
        task = cmmnTaskService.createTaskBuilder().
            assignee("testAssignee").
            create();
        Authentication.setAuthenticatedUserId("testUser");
        try {
            functionToAssert.accept(task.getId());

            List<TaskLogEntry> taskLogsByTaskInstanceId = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(
                taskLogsByTaskInstanceId
            ).size().isEqualTo(2);

            assertThat(taskLogsByTaskInstanceId.get(1)).
                extracting(TaskLogEntry::getUserId).isEqualTo("testUser");
        } finally {
            Authentication.setAuthenticatedUserId(previousUserId);
        }
    }

    @Test
    public void changeDueDate() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        cmmnTaskService.setDueDate(task.getId(), new Date(0));
        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        assertThat(taskLogEntries).size().isEqualTo(2);
        assertThat(taskLogEntries.get(1).getData()).
            contains("\"newDueDate\":0").
            contains("\"previousDueDate\":null");
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTimeStamp).isNotNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getUserId).isNull();
        assertThat(taskLogEntries.get(1)).extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_DUEDATE_CHANGED");
    }

    @Test
    public void saveTask() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        task.setAssignee("newAssignee");
        task.setOwner("newOwner");
        task.setPriority(Integer.MAX_VALUE);
        task.setDueDate(new Date(0));
        cmmnTaskService.saveTask(task);

        List<TaskLogEntry> taskLogEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
        assertThat(taskLogEntries).size().isEqualTo(5);
    }

    @Test
    public void changeDueDateEventAsAuthenticatedUser() {
        assertThatAuthenticatedUserIsSet(
            taskId ->  cmmnTaskService.setDueDate(taskId, new Date(0))
        );
    }

    @Test
    public void createCustomTaskEventLog() {
        task = cmmnTaskService.createTaskBuilder().
            create();
        TaskLogEntryBuilder taskLogEntryBuilder = cmmnHistoryService.createTaskLogEntryBuilder(task);
        taskLogEntryBuilder.timeStamp(new Date(0));
        taskLogEntryBuilder.userId("testUser");
        taskLogEntryBuilder.type("customType");
        taskLogEntryBuilder.data("testData");
        taskLogEntryBuilder.add();

        List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        MatcherAssert.assertThat(logEntries.size(), is(2));
        TaskLogEntry taskLogEntry = logEntries.get(1);
        assertThat(taskLogEntry.getLogNumber()).isNotNull();
        assertThat(taskLogEntry.getUserId()).isEqualTo("testUser");
        assertThat(taskLogEntry.getTaskId()).isEqualTo(task.getId());
        assertThat(taskLogEntry.getType()).isEqualTo("customType");
        assertThat(taskLogEntry.getTimeStamp()).isEqualTo(new Date(0));
        assertThat(taskLogEntry.getData()).isEqualTo("testData");
        cmmnHistoryService.deleteTaskLogEntry(logEntries.get(0).getLogNumber());
    }

    @Test
    public void createCustomTaskEventLog_taskIdIsEnoughToCreateTaskLogEntry() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        TaskLogEntryBuilder taskLogEntryBuilder = cmmnHistoryService.createTaskLogEntryBuilder(task);
        taskLogEntryBuilder.add();
        List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        MatcherAssert.assertThat(logEntries.size(), is(2));
        TaskLogEntry taskLogEntry = logEntries.get(1);
        assertThat(taskLogEntry.getLogNumber()).isNotNull();
        assertThat(taskLogEntry.getUserId()).isNull();
        assertThat(taskLogEntry.getTaskId()).isEqualTo(task.getId());
        assertThat(taskLogEntry.getType()).isNull();
        assertThat(taskLogEntry.getTimeStamp()).isNotNull();
        assertThat(taskLogEntry.getData()).isNull();
    }

    @Test
    public void createCustomTaskEventLog_withoutTimeStamp_addsDefault() {
        task = cmmnTaskService.createTaskBuilder().
            create();

        TaskLogEntryBuilder taskLogEntryBuilder = cmmnHistoryService.createTaskLogEntryBuilder(task);
        taskLogEntryBuilder.userId("testUser");
        taskLogEntryBuilder.type("customType");
        taskLogEntryBuilder.data("testData");
        taskLogEntryBuilder.add();

        List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();

        MatcherAssert.assertThat(logEntries.size(), is(2));
        TaskLogEntry taskLogEntry = logEntries.get(1);
        assertThat(taskLogEntry.getLogNumber()).isNotNull();
        assertThat(taskLogEntry.getTimeStamp()).isNotNull();
    }

    @Test
    public void logCaseTaskEvents() {
        deployOneHumanTaskCaseModel();
        CaseInstance oneTaskCase = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("oneTaskCase").start();
        assertNotNull(oneTaskCase);

        Task task = cmmnTaskService.createTaskQuery().caseInstanceId(oneTaskCase.getId()).singleResult();
        assertNotNull(task);
        try {
            cmmnTaskService.setAssignee(task.getId(), "newAssignee");
            cmmnTaskService.setOwner(task.getId(), "newOwner");
            cmmnTaskService.complete(task.getId());

            List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(logEntries).size().isEqualTo(4);
            assertThat(logEntries.get(0)).
                extracting(taskLogEntry -> taskLogEntry.getType()).isEqualTo("USER_TASK_CREATED")
            ;
            assertThat(logEntries.get(0)).
                extracting(taskLogEntry -> taskLogEntry.getScopeType()).isEqualTo(ScopeTypes.CMMN)
            ;
            assertThat(logEntries.get(0)).
                extracting(taskLogEntry -> taskLogEntry.getScopeId()).isEqualTo(oneTaskCase.getId())
            ;
            assertThat(logEntries.get(0)).
                extracting(taskLogEntry -> taskLogEntry.getSubScopeId()).isEqualTo(task.getSubScopeId())
            ;
            assertThat(logEntries.get(0)).
                extracting(taskLogEntry -> taskLogEntry.getScopeDefinitionId()).isEqualTo(oneTaskCase.getCaseDefinitionId())
            ;
            assertThat(logEntries.get(1)).
                extracting(taskLogEntry -> taskLogEntry.getType()).isEqualTo("USER_TASK_ASSIGNEE_CHANGED")
            ;
            assertThat(logEntries.get(2)).
                extracting(taskLogEntry -> taskLogEntry.getType()).isEqualTo("USER_TASK_OWNER_CHANGED")
            ;
            assertThat(logEntries.get(3)).
                extracting(taskLogEntry -> taskLogEntry.getType()).isEqualTo("USER_TASK_COMPLETED")
            ;
        } finally {
            deleteTaskWithLogEntries(task.getId());
        }
    }

    @Test
    public void logAddCandidateUser() {
        deployOneHumanTaskCaseModel();
        CaseInstance oneTaskCase = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("oneTaskCase").start();
        assertNotNull(oneTaskCase);
        Task task = cmmnTaskService.createTaskQuery().caseInstanceId(oneTaskCase.getId()).singleResult();

        try {
            assertNotNull(oneTaskCase);
            assertNotNull(task);

            cmmnTaskService.addUserIdentityLink(task.getId(), "newCandidateUser", IdentityLinkType.CANDIDATE);

            List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(logEntries).size().isEqualTo(2);
            assertThat(logEntries.get(1)).
                extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_IDENTITY_LINK_ADDED");
            assertThat(logEntries.get(1).getData()).contains(
                "\"type\":\"candidate\"",
                "\"userId\":\"newCandidateUser\""
            );
        } finally {
            cmmnTaskService.complete(task.getId());
            deleteTaskWithLogEntries(task.getId());
        }
    }

    @Test
    public void logAddCandidateGroup() {
        deployOneHumanTaskCaseModel();
        CaseInstance oneTaskCase = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("oneTaskCase").start();
        assertNotNull(oneTaskCase);
        Task task = cmmnTaskService.createTaskQuery().caseInstanceId(oneTaskCase.getId()).singleResult();
        assertNotNull(task);
        try {

            cmmnTaskService.addGroupIdentityLink(task.getId(), "newCandidateGroup", IdentityLinkType.CANDIDATE);

            List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(logEntries).size().isEqualTo(2);
            assertThat(logEntries.get(1)).
                extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_IDENTITY_LINK_ADDED");
            assertThat(new String(logEntries.get(1).getData())).contains(
                "\"type\":\"candidate\"",
                "\"groupId\":\"newCandidateGroup\""
            );
        } finally {
            cmmnTaskService.complete(task.getId());
            deleteTaskWithLogEntries(task.getId());
        }
    }

    @Test
    public void logDeleteCandidateGroup() {
        deployOneHumanTaskCaseModel();
        CaseInstance oneTaskCase = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("oneTaskCase").start();
        assertNotNull(oneTaskCase);
        Task task = cmmnTaskService.createTaskQuery().caseInstanceId(oneTaskCase.getId()).singleResult();
        assertNotNull(task);

        cmmnTaskService.addGroupIdentityLink(task.getId(), "newCandidateGroup", IdentityLinkType.CANDIDATE);
        try {

            cmmnTaskService.deleteGroupIdentityLink(task.getId(), "newCandidateGroup", IdentityLinkType.CANDIDATE);

            List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(logEntries).size().isEqualTo(3);
            assertThat(logEntries.get(2)).
                extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_IDENTITY_LINK_REMOVED");
            assertThat(new String(logEntries.get(2).getData())).contains(
                "\"type\":\"candidate\"",
                "\"groupId\":\"newCandidateGroup\""
            );
        } finally {
            cmmnTaskService.complete(task.getId());
            deleteTaskWithLogEntries(task.getId());
        }
    }

    @Test
    public void logDeleteCandidateUser() {
        deployOneHumanTaskCaseModel();
        CaseInstance oneTaskCase = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("oneTaskCase").start();
        assertNotNull(oneTaskCase);
        Task task = cmmnTaskService.createTaskQuery().caseInstanceId(oneTaskCase.getId()).singleResult();
        assertNotNull(task);
        cmmnTaskService.addUserIdentityLink(task.getId(), "newCandidateUser", IdentityLinkType.CANDIDATE);

        try {
            cmmnTaskService.deleteUserIdentityLink(task.getId(), "newCandidateUser", IdentityLinkType.CANDIDATE);
            List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(logEntries).size().isEqualTo(3);
            assertThat(logEntries.get(2)).
                extracting(TaskLogEntry::getType).isEqualTo("USER_TASK_IDENTITY_LINK_REMOVED");
            assertThat(new String(logEntries.get(2).getData())).contains(
                "\"type\":\"candidate\"",
                "\"userId\":\"newCandidateUser\""
            );
        } finally {
            cmmnTaskService.complete(task.getId());
            deleteTaskWithLogEntries(task.getId());
        }
    }

    @Test
    public void queryForTaskLogEntriesByTasKId() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("testAssignee").
            create();
        Task anotherTask = cmmnTaskService.createTaskBuilder().create();

        try {
            List<TaskLogEntry> logEntries = cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).list();
            assertThat(logEntries.size()).isEqualTo(1);
            assertThat(logEntries.get(0)).extracting(TaskLogEntry::getTaskId).isEqualTo(task.getId());

            assertThat(
                cmmnHistoryService.createTaskLogEntryQuery().taskId(task.getId()).count()
            ).isEqualTo(1l);
        } finally {
            deleteTaskWithLogEntries(anotherTask.getId());
        }
    }

    @Test
    public void queryForTaskLogEntriesByUserId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().userId("testUser"),
            cmmnHistoryService.createTaskLogEntryQuery().userId("testUser")
        );
    }

    protected void assertThatTaskLogIsFetched(TaskLogEntryBuilder taskLogEntryBuilder, TaskLogEntryQuery taskLogEntryQuery) {
        task = cmmnTaskService.createTaskBuilder().
            assignee("testAssignee").
            create();
        Task anotherTask = cmmnTaskService.createTaskBuilder().create();
        taskLogEntryBuilder.taskId(task.getId()).add();
        taskLogEntryBuilder.taskId(task.getId()).add();
        taskLogEntryBuilder.taskId(task.getId()).add();

        try {
            List<TaskLogEntry> logEntries = taskLogEntryQuery.list();
            assertThat(logEntries.size()).isEqualTo(3);
            assertThat(logEntries).extracting(TaskLogEntry::getTaskId).containsExactly(task.getId(), task.getId(), task.getId());

            assertThat(
                taskLogEntryQuery.count()
            ).isEqualTo(3l);

            List<TaskLogEntry> pagedLogEntries = taskLogEntryQuery.listPage(1, 1);
            assertThat(pagedLogEntries.size()).isEqualTo(1);
            assertThat(pagedLogEntries.get(0)).isEqualToComparingFieldByField(logEntries.get(1));
        } finally {
            deleteTaskWithLogEntries(anotherTask.getId());
        }
    }

    @Test
    public void queryForTaskLogEntriesByType() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().type("testType"),
            cmmnHistoryService.createTaskLogEntryQuery().type("testType")
        );
    }

    @Test
    public void queryForTaskLogEntriesByProcessInstanceId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().processInstanceId("testProcess"),
            cmmnHistoryService.createTaskLogEntryQuery().processInstanceId("testProcess")
        );
    }

    @Test
    public void queryForTaskLogEntriesByScopeId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().scopeId("testScopeId"),
            cmmnHistoryService.createTaskLogEntryQuery().scopeId("testScopeId")
        );
    }

    @Test
    public void queryForTaskLogEntriesBySubScopeId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().subScopeId("testSubScopeId"),
            cmmnHistoryService.createTaskLogEntryQuery().subScopeId("testSubScopeId")
        );
    }

    @Test
    public void queryForTaskLogEntriesByScopeType() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().scopeType("testScopeType"),
            cmmnHistoryService.createTaskLogEntryQuery().scopeType("testScopeType")
        );
    }

    @Test
    public void queryForTaskLogEntriesByFromTimeStamp() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().timeStamp(new Date(Long.MAX_VALUE / 2)),
            cmmnHistoryService.createTaskLogEntryQuery().from(new Date(Long.MAX_VALUE / 2 - 1))
        );
    }

    @Test
    public void queryForTaskLogEntriesByFromIncludedTimeStamp() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().timeStamp(new Date(Long.MAX_VALUE / 2)),
            cmmnHistoryService.createTaskLogEntryQuery().from(new Date(Long.MAX_VALUE / 2))
        );
    }

    @Test
    public void queryForTaskLogEntriesByToTimeStamp() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().timeStamp(new Date(0)),
            cmmnHistoryService.createTaskLogEntryQuery().to(new Date(1))
        );
    }

    @Test
    public void queryForTaskLogEntriesByToIncludedTimeStamp() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().timeStamp(new Date(0)),
            cmmnHistoryService.createTaskLogEntryQuery().to(new Date(1))
        );
    }

    @Test
    public void queryForTaskLogEntriesByFromToTimeStamp() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().timeStamp(new Date(0)),
            cmmnHistoryService.createTaskLogEntryQuery().from(new Date(-1)).to(new Date(1))
        );
    }

    @Test
    public void queryForTaskLogEntriesByTenantId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().timeStamp(new Date(0)),
            cmmnHistoryService.createTaskLogEntryQuery().from(new Date(-1)).to(new Date(1))
        );
    }

    @Test
    public void queryForTaskLogEntriesByProcessDefinitionId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().processDefinitionId("testProcessDefinitionId"),
            cmmnHistoryService.createTaskLogEntryQuery().processDefinitionId("testProcessDefinitionId")
        );
    }

    @Test
    public void queryForTaskLogEntriesByScopeDefinitionId() {
        assertThatTaskLogIsFetched(
            cmmnHistoryService.createTaskLogEntryBuilder().scopeDefinitionId("testScopeDefinitionId"),
            cmmnHistoryService.createTaskLogEntryQuery().scopeDefinitionId("testScopeDefinitionId")
        );
    }

    @Test
    public void queryForTaskLogEntriesByLogNumber() {
        task = cmmnTaskService.createTaskBuilder().
            assignee("testAssignee").
            create();
        Task anotherTask = cmmnTaskService.createTaskBuilder().create();
        TaskLogEntryBuilder taskLogEntryBuilder = cmmnHistoryService.createTaskLogEntryBuilder();
        taskLogEntryBuilder.taskId(task.getId()).add();
        taskLogEntryBuilder.taskId(task.getId()).add();
        taskLogEntryBuilder.taskId(task.getId()).add();

        List<TaskLogEntry> allLogEntries = cmmnHistoryService.createTaskLogEntryQuery().list();

        try {
            TaskLogEntryQuery taskLogEntryQuery = cmmnHistoryService.createTaskLogEntryQuery().
                fromLogNumber(allLogEntries.get(1).getLogNumber()).
                toLogNumber(allLogEntries.get(allLogEntries.size() - 2).getLogNumber());
            List<TaskLogEntry> logEntries = taskLogEntryQuery.
                list();
            assertThat(logEntries.size()).isEqualTo(3);
            assertThat(logEntries).extracting(TaskLogEntry::getTaskId).containsExactly(anotherTask.getId(), task.getId(), task.getId());

            assertThat(
                taskLogEntryQuery.count()
            ).isEqualTo(3l);

            List<TaskLogEntry> pagedLogEntries = taskLogEntryQuery.listPage(1, 1);
            assertThat(pagedLogEntries.size()).isEqualTo(1);
            assertThat(pagedLogEntries.get(0)).isEqualToComparingFieldByField(logEntries.get(1));
        } finally {
            deleteTaskWithLogEntries(anotherTask.getId());
        }
    }

    @Test
    public void queryForTaskLogEntriesByNativeQuery() {
        TaskLogEntryBuilder taskLogEntryBuilder = cmmnHistoryService.createTaskLogEntryBuilder();
        taskLogEntryBuilder.taskId("1").add();
        taskLogEntryBuilder.taskId("2").add();
        taskLogEntryBuilder.taskId("3").add();

        try {
            assertEquals(3,
                cmmnHistoryService.createNativeTaskLogEntryQuery().sql("SELECT * FROM ACT_HI_TSK_LOG").list().size());
            assertEquals(3,
                cmmnHistoryService.createNativeTaskLogEntryQuery().sql("SELECT count(*) FROM ACT_HI_TSK_LOG").count());

            assertEquals(1, cmmnHistoryService.createNativeTaskLogEntryQuery().parameter("taskId", "1").
                sql("SELECT * FROM ACT_HI_TSK_LOG WHERE TASK_ID_ = #{taskId}").list().size());
            assertEquals(1, cmmnHistoryService.createNativeTaskLogEntryQuery().parameter("taskId", "1").
                sql("SELECT count(*) FROM ACT_HI_TSK_LOG WHERE TASK_ID_ = #{taskId}").count());
        } finally {
            cmmnHistoryService.createTaskLogEntryQuery().list().
                forEach(
                    logEntry -> cmmnHistoryService.deleteTaskLogEntry(logEntry.getLogNumber())
                );
        }
    }
}
