package it.gov.innovazione.ndc.harvester.service.startupjob;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.minBy;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.innovazione.ndc.harvester.HarvesterJob;
import it.gov.innovazione.ndc.harvester.JobExecutionResponse;
import it.gov.innovazione.ndc.harvester.service.HarvesterRunService;
import it.gov.innovazione.ndc.harvester.service.RepositoryService;
import it.gov.innovazione.ndc.model.harvester.HarvesterRun;
import it.gov.innovazione.ndc.model.harvester.Repository;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatsPopulator implements StartupJob {

  private final RepositoryService repositoryService;
  private final HarvesterJob harvesterJob;
  private final HarvesterRunService harvesterRunService;

  @Value("classpath:runs_by_date.json")
  private final Resource runsByDatePath;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private Map<Date, List<RepoAndSha>> runsByDate = null;

  private static final TypeReference<Map<Date, List<RepoAndSha>>> TYPE_REFERENCE =
      new TypeReference<Map<Date, List<RepoAndSha>>>() {};

  @PostConstruct
  public void init() {
    try {
      runsByDate = objectMapper.readValue(runsByDatePath.getInputStream(), TYPE_REFERENCE);
    } catch (Exception e) {
      log.error("Error reading runs by date", e);
    }
  }

  @Override
  public void run() {
    log.info("Populating stats");
    populateRepoIfNecessary();
    harvestRepositories();
  }

  private void harvestRepositories() {
    Map<String, String> repoIdByUrl =
        repositoryService.getActiveRepos().stream()
            .collect(toMap(Repository::getUrl, Repository::getId));
    runsByDate.forEach(
        (date, repos) -> {
          log.info("Harvesting repos for date {}", date);
          repos.forEach(
              repoAndSha -> {
                log.info("Harvesting repo {} for date {}", repoAndSha.getUrl(), date);
                if (repoIdByUrl.containsKey(repoAndSha.getUrl())) {
                    JobExecutionResponse harvest = harvesterJob.harvest(
                            repoIdByUrl.get(repoAndSha.getUrl()), repoAndSha.getSha(), false);
                    updateHarvesterRun(harvest, date);
                } else {
                  log.warn("Repo {} not found", repoAndSha.getUrl());
                }
              });
        });
  }

    private void updateHarvesterRun(JobExecutionResponse harvest, Date date) {
        HarvesterRun byId = harvesterRunService.getById(harvest.getRunId());
        Instant newStart =
                LocalDateTime.of(
                        LocalDate.ofInstant(date.toInstant(), ZoneId.systemDefault()),
                        byId.getStartedAt().atZone(ZoneId.systemDefault()).toLocalTime())
                        .atZone(ZoneId.systemDefault()).toInstant();
        Instant newEnd =
                LocalDateTime.of(
                        LocalDate.ofInstant(date.toInstant(), ZoneId.systemDefault()),
                        byId.getEndedAt().atZone(ZoneId.systemDefault()).toLocalTime())
                        .atZone(ZoneId.systemDefault()).toInstant();

        harvesterRunService.updateHarvesterRunStarted(byId.getId(), newStart, newEnd );
    }

    private void populateRepoIfNecessary() {
    Map<String, Repository> repoByUrl =
        repositoryService.getActiveRepos().stream()
            .collect(
                groupingBy(
                    Repository::getUrl,
                    collectingAndThen(
                        minBy(Comparator.comparing(Repository::getCreatedAt)),
                        opt -> opt.orElse(null))));
    Map<String, Date> collect =
        runsByDate.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                e -> {
                  Date date = e.getKey();
                  List<RepoAndSha> repos = e.getValue();
                  return repos.stream()
                      .map(repoAndSha -> Pair.of(date, repoAndSha.getUrl()))
                      .toList();
                })
            .flatMap(List::stream)
            .filter(pair -> !repoByUrl.containsKey(pair.getRight()))
            .collect(
                groupingBy(
                    Pair::getRight,
                    collectingAndThen(
                        minBy(Comparator.comparing(Pair::getLeft)),
                        opt -> opt.map(Pair::getLeft).orElse(null))));

    collect.forEach(
        (url, date) -> {
          log.info("Creating repo {} with date {}", url, date);
          repositoryService.createRepo(
              url, url, url, null, () -> "config", Optional.of(date.toInstant()));
        });
  }

  @Data
  static class RepoAndSha {
    private String url;
    private String sha;
  }
}
