/*
 * Copyright 2019 Pivotal, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.igor.concourse.service;

import com.netflix.spinnaker.igor.build.model.GenericBuild;
import com.netflix.spinnaker.igor.build.model.GenericGitRevision;
import com.netflix.spinnaker.igor.build.model.Result;
import com.netflix.spinnaker.igor.concourse.client.ConcourseClient;
import com.netflix.spinnaker.igor.concourse.client.model.*;
import com.netflix.spinnaker.igor.config.ConcourseProperties;
import com.netflix.spinnaker.igor.model.BuildServiceProvider;
import com.netflix.spinnaker.igor.service.BuildProperties;
import com.netflix.spinnaker.igor.service.BuildService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import javax.annotation.Nullable;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.*;

@Slf4j
public class ConcourseService implements BuildService, BuildProperties {
  private final ConcourseProperties.Host host;
  private final ConcourseClient client;

  @Nullable
  private final Pattern resourceFilter;

  public ConcourseService(ConcourseProperties.Host host) {
    this.host = host;
    this.client = new ConcourseClient(host.getUrl(), host.getUsername(), host.getPassword());
    this.resourceFilter = host.getResourceFilterRegex() == null ? null : Pattern.compile(host.getResourceFilterRegex());
  }

  public String getMaster() {
    return "concourse-" + host.getName();
  }

  @Override
  public BuildServiceProvider buildServiceProvider() {
    return BuildServiceProvider.CONCOURSE;
  }

  public Collection<Team> teams() {
    refreshTokenIfNecessary();
    return client.getTeamService().teams().stream()
      .filter(team -> host.getTeams() == null || host.getTeams().contains(team.getName()))
      .collect(toList());
  }

  public Collection<Pipeline> pipelines() {
    refreshTokenIfNecessary();
    return client.getPipelineService().pipelines().stream()
      .filter(pipeline -> host.getTeams() == null || host.getTeams().contains(pipeline.getTeamName()))
      .collect(toList());
  }

  public Collection<Job> getJobs() {
    refreshTokenIfNecessary();
    return client.getJobService().jobs().stream()
      .filter(job -> host.getTeams() == null || host.getTeams().contains(job.getTeamName()))
      .collect(toList());
  }

  @Override
  public List<GenericGitRevision> getGenericGitRevisions(String jobPath, int buildNumber) {
    GenericBuild build = getGenericBuild(jobPath, buildNumber);
    return build == null ? emptyList() : build.getGenericGitRevisions();
  }

  @Override
  public Map<String, ?> getBuildProperties(String jobPath, int buildNumber, String fileName) {
    GenericBuild build = getGenericBuild(jobPath, buildNumber);
    return build == null ? emptyMap() : build.getProperties();
  }

  @Nullable
  @Override
  public GenericBuild getGenericBuild(String jobPath, int buildNumber) {
    return getBuilds(jobPath).stream()
      .filter(build -> build.getNumber() == buildNumber)
      .findAny()
      .map(build -> getGenericBuild(jobPath, build, true))
      .orElse(null);
  }

  public GenericBuild getGenericBuild(String jobPath, Build b, boolean fetchResources) {
    Job job = toJob(jobPath);

    GenericBuild build = new GenericBuild();
    build.setId(b.getId());
    build.setBuilding(false);
    build.setNumber(b.getNumber());
    build.setResult(Result.SUCCESS);
    build.setName(job.getName());
    build.setFullDisplayName(job.getTeamName() + "/" + job.getPipelineName() + "/" + job.getName());
    build.setUrl(host.getUrl() + "/teams/" + job.getTeamName() + "/pipelines/" + job.getPipelineName() + "/jobs/" +
      job.getName() + "/builds/" + b.getNumber());

    if (!fetchResources) {
      return build;
    }

    Collection<Resource> resources = getResources(b.getId());

    // merge input and output metadata into one map for each resource
    Map<String, Map<String, String>> mergedMetadataByResourceName = resources.stream()
      .collect(
        groupingBy(Resource::getName,
          reducing(emptyMap(), Resource::getMetadata,
            (m1, m2) -> {
              Map<String, String> m1OrEmpty = m1 == null ? emptyMap() : m1;
              Map<String, String> m2OrEmpty = m2 == null ? emptyMap() : m2;
              return Stream.concat(m1OrEmpty.entrySet().stream(), m2OrEmpty.entrySet().stream())
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            }))
      );

    // extract git information from this particular named resource type
    resources.stream().filter(r -> r.getType().equals("git"))
      .map(Resource::getName)
      .findAny()
      .ifPresent(gitResourceName -> {
        Map<String, String> git = mergedMetadataByResourceName.remove(gitResourceName);
        if (git != null) {
          String message = git.get("message");
          String timestamp = git.get("committer_date");
          build.setGenericGitRevisions(Collections.singletonList(GenericGitRevision.builder()
            .committer(git.get("committer"))
            .branch(git.get("branch"))
            .name(git.get("branch"))
            .message(message == null ? null : message.trim())
            .sha1(git.get("commit"))
            .timestamp(timestamp == null ? null : ZonedDateTime.parse(timestamp,
              DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")).toInstant())
            .build()));
        }
      });

    if (!mergedMetadataByResourceName.isEmpty()) {
      build.setProperties(mergedMetadataByResourceName);
    }

    return build;
  }

  private Collection<Resource> getResources(String buildId) {
    Map<String, Resource> resources = client.getBuildService().plan(buildId)
      .getResources()
      .stream()
      .filter(r -> resourceFilter == null
        || "git".equals(r.getType()) // there is a place for Git revision history on GenericBuild
        || resourceFilter.matcher(r.getType()).matches())
      .collect(toMap(Resource::getId, Function.identity()));

    if (!resources.isEmpty()) {
      setResourceMetadata(buildId, resources);
    }

    return resources.values();
  }

  /**
   * Uses Concourse's build event stream to locate and populate resource metadata
   */
  private void setResourceMetadata(String buildId, Map<String, Resource> resources) {
    Flux<Event> events = client.getEventService().resourceEvents(buildId);
    CountDownLatch latch = new CountDownLatch(resources.size());

    Disposable eventStream = events
      .doOnNext(event -> {
        Resource resource = resources.get(event.getResourceId());
        if (resource != null) {
          resource.setMetadata(event.getData().getMetadata());
          latch.countDown();
        }
      })
      .doOnComplete(() -> {
        // if the event stream has ended, just count down the rest of the way
        while (latch.getCount() > 0) {
          latch.countDown();
        }
      })
      .subscribe();

    try {
      latch.await();
    } catch (InterruptedException e) {
      log.warn("Unable to fully read event stream", e);
    } finally {
      eventStream.dispose();
    }
  }

  @Override
  public int triggerBuildWithParameters(String job, Map<String, String> queryParameters) {
    throw new UnsupportedOperationException("Triggering concourse builds not supported");
  }

  @Override
  public List<Build> getBuilds(String jobPath) {
    return getBuilds(jobPath, null);
  }

  public List<Build> getBuilds(String jobPath, @Nullable Long since) {
    Job job = toJob(jobPath);

    if (host.getTeams() != null && !host.getTeams().contains(job.getTeamName())) {
      return emptyList();
    }

    return client.getBuildService()
      .builds(job.getTeamName(), job.getPipelineName(), job.getName(), host.getBuildLookbackLimit(), since)
      .stream()
      .filter(b -> "succeeded".equals(b.getStatus()))
      .collect(Collectors.toList());
  }

  private Job toJob(String jobPath) {
    String[] jobParts = jobPath.split("/");
    if (jobParts.length != 3) {
      throw new IllegalArgumentException("job must be in the format teamName/pipelineName/jobName");
    }

    Job job = new Job();
    job.setTeamName(jobParts[0]);
    job.setPipelineName(jobParts[1]);
    job.setName(jobParts[2]);

    return job;
  }

  /**
   * This is necessary until this is resolved: https://github.com/concourse/concourse/issues/3558
   */
  private void refreshTokenIfNecessary() {
    // returns a 401 on expired/invalid token, which because of retry logic causes the token to be refreshed.
    client.getSkyService().userInfo();
  }
}
