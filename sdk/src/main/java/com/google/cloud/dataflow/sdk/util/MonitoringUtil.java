/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import static com.google.cloud.dataflow.sdk.util.TimeUtil.fromCloudTime;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.V1b3.Projects.Jobs.Messages;
import com.google.api.services.dataflow.model.JobMessage;
import com.google.api.services.dataflow.model.ListJobMessagesResponse;
import com.google.cloud.dataflow.sdk.PipelineResult.State;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import org.joda.time.Instant;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A helper class for monitoring jobs submitted to the service.
 */
public final class MonitoringUtil {

  private static final String GCLOUD_DATAFLOW_PREFIX = "gcloud alpha dataflow";

  private static final Map<String, State> DATAFLOW_STATE_TO_JOB_STATE =
      ImmutableMap
          .<String, State>builder()
          .put("JOB_STATE_UNKNOWN", State.UNKNOWN)
          .put("JOB_STATE_STOPPED", State.STOPPED)
          .put("JOB_STATE_RUNNING", State.RUNNING)
          .put("JOB_STATE_DONE", State.DONE)
          .put("JOB_STATE_FAILED", State.FAILED)
          .put("JOB_STATE_CANCELLED", State.CANCELLED)
          .build();

  private String projectId;
  private Messages messagesClient;

  /**
   * An interface that can be used for defining callbacks to receive a list
   * of JobMessages containing monitoring information.
   */
  public interface JobMessagesHandler {
    /** Process the rows. */
    void process(List<JobMessage> messages);
  }

  /** A handler that prints monitoring messages to a stream. */
  public static class PrintHandler implements JobMessagesHandler {
    private PrintStream out;

    /**
     * Construct the handler.
     *
     * @param stream The stream to write the messages to.
     */
    public PrintHandler(PrintStream stream) {
      out = stream;
    }

    @Override
    public void process(List<JobMessage> messages) {
      for (JobMessage message : messages) {
        if (message.getMessageText() == null || message.getMessageText().isEmpty()) {
          continue;
        }
        String importanceString = null;
        if (message.getMessageImportance() == null) {
          continue;
        } else if (message.getMessageImportance().equals("JOB_MESSAGE_ERROR")) {
          importanceString = "Error:   ";
        } else if (message.getMessageImportance().equals("JOB_MESSAGE_WARNING")) {
          importanceString = "Warning: ";
        } else if (message.getMessageImportance().equals("JOB_MESSAGE_DETAILED")) {
          importanceString = "Detail:  ";
        } else {
          // TODO: Remove filtering here once getJobMessages supports minimum
          // importance.
          continue;
        }
        @Nullable Instant time = TimeUtil.fromCloudTime(message.getTime());
        if (time == null) {
          out.print("UNKNOWN TIMESTAMP: ");
        } else {
          out.print(time + ": ");
        }
        if (importanceString != null) {
          out.print(importanceString);
        }
        out.println(message.getMessageText());
      }
      out.flush();
    }
  }

  /** Construct a helper for monitoring. */
  public MonitoringUtil(String projectId, Dataflow dataflow) {
    this(projectId, dataflow.v1b3().projects().jobs().messages());
  }

  // @VisibleForTesting
  MonitoringUtil(String projectId, Messages messagesClient) {
    this.projectId = projectId;
    this.messagesClient = messagesClient;
  }

  /**
   * Comparator for sorting rows in increasing order based on timestamp.
   */
  public static class TimeStampComparator implements Comparator<JobMessage> {
    @Override
    public int compare(JobMessage o1, JobMessage o2) {
      @Nullable Instant t1 = fromCloudTime(o1.getTime());
      if (t1 == null) {
        return -1;
      }
      @Nullable Instant t2 = fromCloudTime(o2.getTime());
      if (t2 == null) {
        return 1;
      }
      return t1.compareTo(t2);
    }
  }

  /**
   * Return job messages sorted in ascending order by timestamp.
   * @param jobId The id of the job to get the messages for.
   * @param startTimestampMs Return only those messages with a
   *   timestamp greater than this value.
   * @return collection of messages
   * @throws IOException
   */
  public ArrayList<JobMessage> getJobMessages(
      String jobId, long startTimestampMs) throws IOException {
    // TODO: Allow filtering messages by importance
    Instant startTimestamp = new Instant(startTimestampMs);
    ArrayList<JobMessage> allMessages = new ArrayList<>();
    String pageToken = null;
    while (true) {
      Messages.List listRequest = messagesClient.list(projectId, jobId);
      if (pageToken != null) {
        listRequest.setPageToken(pageToken);
      }
      ListJobMessagesResponse response = listRequest.execute();

      if (response == null || response.getJobMessages() == null) {
        return allMessages;
      }

      for (JobMessage m : response.getJobMessages()) {
        @Nullable Instant timestamp = fromCloudTime(m.getTime());
        if (timestamp == null) {
          continue;
        }
        if (timestamp.isAfter(startTimestamp)) {
          allMessages.add(m);
        }
      }

      if (response.getNextPageToken() == null) {
        break;
      } else {
        pageToken = response.getNextPageToken();
      }
    }

    Collections.sort(allMessages, new TimeStampComparator());
    return allMessages;
  }

  public static String getJobMonitoringPageURL(String projectName, String jobId) {
    try {
      // Project name is allowed in place of the project id: the user will be redirected to a URL
      // that has the project name replaced with project id.
      return String.format(
          "https://console.developers.google.com/project/%s/dataflow/job/%s",
          URLEncoder.encode(projectName, "UTF-8"),
          URLEncoder.encode(jobId, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      // Should never happen.
      throw new AssertionError("UTF-8 encoding is not supported by the environment", e);
    }
  }

  public static String getGcloudCancelCommand(String projectName, String jobId) {
    return String.format("%s jobs --project=%s cancel %s",
        GCLOUD_DATAFLOW_PREFIX, projectName, jobId);
  }

  public static State toState(String stateName) {
    return MoreObjects.firstNonNull(DATAFLOW_STATE_TO_JOB_STATE.get(stateName),
        State.UNKNOWN);
  }
}
