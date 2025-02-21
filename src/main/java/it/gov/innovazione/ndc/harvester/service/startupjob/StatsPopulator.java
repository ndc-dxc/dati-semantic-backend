package it.gov.innovazione.ndc.harvester.service.startupjob;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.innovazione.ndc.harvester.HarvesterJob;
import it.gov.innovazione.ndc.harvester.JobExecutionResponse;
import it.gov.innovazione.ndc.harvester.service.HarvesterRunService;
import it.gov.innovazione.ndc.harvester.service.RepositoryService;
import it.gov.innovazione.ndc.model.harvester.HarvesterRun;
import it.gov.innovazione.ndc.model.harvester.Repository;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.util.Base64.getEncoder;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.minBy;
import static java.util.stream.Collectors.toMap;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatsPopulator implements StartupJob {

    private final RepositoryService repositoryService;
    private final HarvesterJob harvesterJob;
    private final HarvesterRunService harvesterRunService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("classpath:runs_by_date.json")
    private final Resource runsByDatePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<Date, List<RepoAndSha>> runsByDate = null;

    private static final TypeReference<Map<Date, List<RepoAndSha>>> TYPE_REFERENCE =
            new TypeReference<Map<Date, List<RepoAndSha>>>() {
            };

    @PostConstruct
    public void init() {
        try {
            runsByDate = new HashMap<>(objectMapper.readValue(runsByDatePath.getInputStream(), TYPE_REFERENCE));
            runsByDate.putAll(getNewerRuns());
        } catch (Exception e) {
            log.error("Error reading runs by date", e);
        }
    }

    private Map<Date, List<RepoAndSha>> getNewerRuns() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + getEncoder().encodeToString(("harv-user:harv-password").getBytes()));
        headers.setContentType(APPLICATION_JSON);
        ResponseEntity<List<HarvesterRun>> exchange = restTemplate.exchange(
                "https://ndc-prod-blue.apps.cloudpub.istat.it/api/jobs/harvest/run",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                });
        List<HarvesterRun> body = exchange.getBody();
        Instant maxRunsByDate = runsByDate.keySet()
                .stream()
                .max(Comparator.naturalOrder())
                .map(Date::toInstant)
                .orElse(Instant.EPOCH);

        List<HarvesterRun> list = body.stream()
                .filter(harvesterRun -> harvesterRun.getStartedAt().plus(1, DAYS).isAfter(maxRunsByDate))
                .filter(harvesterRun -> harvesterRun.getStatus() == HarvesterRun.Status.SUCCESS)
                .filter(harvesterRun -> isNotInOldRuns(harvesterRun))
                .toList();

        Map<Date, List<RepoAndSha>> collect = list.stream()
                .collect(groupingBy(
                        a -> Date.from(a.getStartedAt()),
                        mapping(a -> {
                            RepoAndSha b = new RepoAndSha();
                            b.setUrl(a.getRepositoryUrl());
                            b.setSha(a.getRevision());
                            return b;
                        }, Collectors.toList())));

        return collect;

    }

    private boolean isNotInOldRuns(HarvesterRun harvesterRun) {
        return runsByDate.values()
                .stream()
                .flatMap(List::stream)
                .noneMatch(repoAndSha ->
                        repoAndSha.url.equals(harvesterRun.getRepositoryUrl()) &&
                                repoAndSha.sha.equals(harvesterRun.getRevision()));
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
        List<HarvesterRun> allRuns = harvesterRunService.getAllRuns();
        runsByDate.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(
                dateListEntry -> {
                    Date date = dateListEntry.getKey();
                    List<RepoAndSha> repos = dateListEntry.getValue();
                    log.info("Harvesting repos for date {}", date);
                    repos.stream()
                        .filter(repoAndSha -> wasNotHarvestedAlready(allRuns, repoAndSha))
                        .forEach(
                            repoAndSha -> {
                                log.info("Harvesting repo {} for date {}", repoAndSha.getUrl(), date);
                                if (repoIdByUrl.containsKey(repoAndSha.getUrl())) {
                                    JobExecutionResponse harvest =
                                        harvesterJob.harvest(
                                            repoIdByUrl.get(repoAndSha.getUrl()), repoAndSha.getSha(), false);
                                    updateHarvesterRun(harvest, date);
                                } else {
                                    log.warn("Repo {} not found", repoAndSha.getUrl());
                                }
                                });
                });
    }

    private boolean wasNotHarvestedAlready(List<HarvesterRun> allRuns, RepoAndSha repoAndSha) {
        Set<HarvesterRun> collect =
                allRuns.stream()
                        .filter(harvesterRun -> harvesterRun.getStatus() == HarvesterRun.Status.SUCCESS)
                        .filter(
                                harvesterRun ->
                                        harvesterRun.getRepositoryUrl().equals(repoAndSha.getUrl())
                                                && harvesterRun.getRevision().equals(repoAndSha.getSha()))
                        .collect(Collectors.toSet());

        collect.forEach(
                harvesterRun -> {
                    log.info("Harvested already: [repo: {}, revision: {}", harvesterRun.getRepositoryUrl(), harvesterRun.getRevision());
                });

        return collect.isEmpty();
    }

    private void updateHarvesterRun(JobExecutionResponse harvest, Date date) {
        HarvesterRun byId = harvesterRunService.getById(harvest.getRunId());
        Instant newStart =
                LocalDateTime.of(
                                LocalDate.ofInstant(date.toInstant(), ZoneId.systemDefault()),
                                byId.getStartedAt().atZone(ZoneId.systemDefault()).toLocalTime())
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
        Instant newEnd =
                LocalDateTime.of(
                                LocalDate.ofInstant(date.toInstant(), ZoneId.systemDefault()),
                                byId.getEndedAt().atZone(ZoneId.systemDefault()).toLocalTime())
                        .atZone(ZoneId.systemDefault())
                        .toInstant();

        harvesterRunService.updateHarvesterRunStarted(byId.getId(), newStart, newEnd);
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
