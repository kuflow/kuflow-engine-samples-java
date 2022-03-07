/*
 * Copyright (c) 2021-present KuFlow S.L.
 *
 * All rights reserved.
 */

package com.kuflow.engine.samples.worker.email.workflow;

import com.kuflow.engine.client.activity.email.EmailActivities;
import com.kuflow.engine.client.activity.email.resource.EmailResource;
import com.kuflow.engine.client.activity.email.resource.SendMailRequestResource;
import com.kuflow.engine.client.activity.kuflow.KuFlowActivities;
import com.kuflow.engine.client.activity.kuflow.resource.CompleteProcessRequestResource;
import com.kuflow.engine.client.activity.kuflow.resource.CompleteProcessResponseResource;
import com.kuflow.engine.client.activity.kuflow.resource.LogRequestResource;
import com.kuflow.engine.client.activity.kuflow.resource.StartProcessRequestResource;
import com.kuflow.engine.client.activity.kuflow.resource.StartProcessResponseResource;
import com.kuflow.engine.client.activity.kuflow.resource.TaskClaimRequestResource;
import com.kuflow.engine.client.activity.kuflow.resource.TaskCompleteRequestResource;
import com.kuflow.engine.client.activity.kuflow.resource.TaskRequestResource;
import com.kuflow.engine.client.activity.kuflow.resource.TaskResponseResource;
import com.kuflow.engine.client.common.resource.WorkflowRequestResource;
import com.kuflow.engine.client.common.resource.WorkflowResponseResource;
import com.kuflow.engine.client.common.util.TemporalUtils;
import com.kuflow.rest.client.resource.ElementValueFieldResource;
import com.kuflow.rest.client.resource.LogLevelResource;
import com.kuflow.rest.client.util.ElementUtils;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class SampleWorkflowImpl implements SampleWorkflow {

    private enum TaskDefinitionCode {
        FILL_INFO,
        SEND_EMAIL,
    }

    private enum ElementDefinitionCode {
        EMAIL_RECIPIENT,
        EMAIL_SUBJECT,
        EMAIL_BODY,
    }

    private final KuFlowActivities kuflowActivities;

    private final EmailActivities emailActivities;

    public SampleWorkflowImpl() {
        RetryOptions defaultRetryOptions = RetryOptions.newBuilder().validateBuildWithDefaults();

        ActivityOptions defaultActivityOptions = ActivityOptions
            .newBuilder()
            .setRetryOptions(defaultRetryOptions)
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setScheduleToCloseTimeout(Duration.ofDays(365))
            .validateAndBuildWithDefaults();

        ActivityOptions asyncActivityOptions = ActivityOptions
            .newBuilder()
            .setRetryOptions(defaultRetryOptions)
            .setStartToCloseTimeout(Duration.ofDays(1))
            .setScheduleToCloseTimeout(Duration.ofDays(365))
            .validateAndBuildWithDefaults();

        this.kuflowActivities =
            Workflow.newActivityStub(
                KuFlowActivities.class,
                defaultActivityOptions,
                Map.of(TemporalUtils.getActivityType(KuFlowActivities.class, "createTaskAndWaitTermination"), asyncActivityOptions)
            );

        this.emailActivities = Workflow.newActivityStub(EmailActivities.class, defaultActivityOptions);
    }

    @Override
    public WorkflowResponseResource runWorkflow(WorkflowRequestResource request) {
        StartProcessResponseResource startProcess = this.startProcess(request.getProcessId());

        TaskResponseResource taskFillInfo = this.createTaskFillInfo(startProcess);

        this.createAutomaticTaskSendEmail(startProcess, taskFillInfo);

        CompleteProcessResponseResource completeProcess = this.completeProcess(request.getProcessId());

        return this.completeWorkflow(completeProcess);
    }

    private WorkflowResponseResource completeWorkflow(CompleteProcessResponseResource completeProcess) {
        WorkflowResponseResource workflowResponse = new WorkflowResponseResource();
        workflowResponse.setMessage(completeProcess.getMessage());

        return workflowResponse;
    }

    private StartProcessResponseResource startProcess(UUID processId) {
        StartProcessRequestResource request = new StartProcessRequestResource();
        request.setProcessId(processId);

        return this.kuflowActivities.startProcess(request);
    }

    private CompleteProcessResponseResource completeProcess(UUID processId) {
        CompleteProcessRequestResource request = new CompleteProcessRequestResource();
        request.setProcessId(processId);

        return this.kuflowActivities.completeProcess(request);
    }

    /**
     * Create a task in KuFlow in order to collect the necessary information to send an email.
     *
     * @param startProcess response of start process activity
     * @return task created
     */
    private TaskResponseResource createTaskFillInfo(StartProcessResponseResource startProcess) {
        TaskRequestResource task = new TaskRequestResource();
        task.setProcessId(startProcess.getProcess().getId());
        task.setTaskDefinitionCode(TaskDefinitionCode.FILL_INFO.name());
        task.setTaskId(Workflow.randomUUID());

        // Create Task in KuFlow
        return this.kuflowActivities.createTaskAndWaitTermination(task);
    }

    /**
     * Execute a Temporal activity that sends an email obtaining the data from a previous KuFlow task.
     *
     * To see the activity process reflected in the KuFlow application, we created a task.
     * The execution of Temporal activities does not have to have direct correspondence with KuFlow tasks. Its use
     * depends on your Workflow logic. In the same way, several activities could be executed and have a single
     * automatic task in Kuflow that encompasses them. As always, it all depends on the requirements of your workflow.
     *
     * @param startProcess response of start process activity
     * @param infoTask kuflow task with the data to send in the email
     * @return task created
     */
    private TaskResponseResource createAutomaticTaskSendEmail(StartProcessResponseResource startProcess, TaskResponseResource infoTask) {
        TaskRequestResource task = new TaskRequestResource();
        task.setTaskId(Workflow.randomUUID());
        task.setProcessId(startProcess.getProcess().getId());
        task.setTaskDefinitionCode(TaskDefinitionCode.SEND_EMAIL.name());

        // Create Automatic Task in KuFlow
        TaskResponseResource taskResponse = this.kuflowActivities.createTask(task);

        // Claim Automatic Task: Our worker will be responsible for its completion.
        TaskClaimRequestResource taskClaimrequestResource = new TaskClaimRequestResource();
        taskClaimrequestResource.setTaskId(taskResponse.getTask().getId());
        this.kuflowActivities.claimTask(taskClaimrequestResource);

        // Get the recipient email from info task
        ElementValueFieldResource emailElementValue = ElementUtils.getSingleValueByCode(
            infoTask.getTask(),
            ElementDefinitionCode.EMAIL_RECIPIENT.name(),
            ElementValueFieldResource.class
        );

        // Get the email body from info task
        ElementValueFieldResource subjectElementValue = ElementUtils.getSingleValueByCode(
            infoTask.getTask(),
            ElementDefinitionCode.EMAIL_SUBJECT.name(),
            ElementValueFieldResource.class
        );

        ElementValueFieldResource bodyElementValue = ElementUtils.getSingleValueByCode(
            infoTask.getTask(),
            ElementDefinitionCode.EMAIL_BODY.name(),
            ElementValueFieldResource.class
        );

        EmailResource email = new EmailResource();
        email.setTemplate("email");
        email.setTo(emailElementValue.getValue());
        email.addVariables("subject", subjectElementValue.getValue());
        email.addVariables("body", bodyElementValue.getValue());

        // Send a mail
        SendMailRequestResource request = new SendMailRequestResource();
        request.setEmail(email);
        this.emailActivities.sendMail(request);

        // Add some logs to Kuflow task in order to see feedback in Kuflow app
        this.addLogInfoEntryTask(taskResponse.getTask().getId(), "Email sent!");

        // Activity Complete
        TaskCompleteRequestResource completeRequest = new TaskCompleteRequestResource();
        completeRequest.setTaskId(taskResponse.getTask().getId());
        this.kuflowActivities.completeTask(completeRequest);

        return taskResponse;
    }

    private void addLogInfoEntryTask(UUID taskId, String message) {
        LogRequestResource request = new LogRequestResource();
        request.setTaskId(taskId);
        request.setLogId(Workflow.randomUUID());
        request.setLevel(LogLevelResource.INFO);
        request.setMessage(message);

        this.kuflowActivities.appendLog(request);
    }
}