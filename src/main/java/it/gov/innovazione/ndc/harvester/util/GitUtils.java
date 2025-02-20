package it.gov.innovazione.ndc.harvester.util;

import static it.gov.innovazione.ndc.harvester.AgencyRepositoryService.TEMP_DIR_PREFIX;

import it.gov.innovazione.ndc.service.logging.LoggingContext;
import it.gov.innovazione.ndc.service.logging.NDCHarvesterLogger;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GitUtils {

  private final FileUtils fileUtils;
  private static final Map<String, Path> PATHS_BY_REPO_URL = new HashMap<>();

  public ClonedRepoInfo cloneRepoAndGetLastCommitDate(
      String repoUrl, Path destination, String revision) {
    try {
      Git call = null;
      Path finalDestination = null;
      if (PATHS_BY_REPO_URL.containsKey(repoUrl)
          && PATHS_BY_REPO_URL.get(repoUrl).toFile().exists()) {
        finalDestination = PATHS_BY_REPO_URL.get(repoUrl);
        call = Git.open(finalDestination.toFile());
        fileUtils.removeDirectory(destination);
      } else {
        finalDestination = destination;
        call = Git.cloneRepository().setURI(repoUrl).setDirectory(finalDestination.toFile()).call();
        PATHS_BY_REPO_URL.put(repoUrl, finalDestination);
      }

      if (StringUtils.isNotBlank(revision)) {
        // Resetta lo stato del repository per eliminare eventuali modifiche locali
        call.reset().setMode(ResetCommand.ResetType.HARD).call();

        // Ora esegui il checkout in modalità detached
        call.checkout()
            .setName(revision) // SHA del commit
            .setForced(true) // Forza il checkout ignorando conflitti
            .call();
      }
      return new ClonedRepoInfo(finalDestination, safelyGetLastCommitDate(call));
    } catch (GitAPIException | IOException e) {
      throw new GitRepoCloneException(String.format("Cannot clone repo '%s'", repoUrl), e);
    }
  }

  private boolean isHead(Ref ref) {
    return StringUtils.endsWith(ref.getName(), "HEAD");
  }

  public String getHeadRemoteRevision(String url) {
    try {
      return Git.lsRemoteRepository().setRemote(url).call().stream()
          .filter(this::isHead)
          .map(Ref::getObjectId)
          .map(AnyObjectId::getName)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("Unable to get HEAD revision"));
    } catch (Exception e) {
      throw new GitRepoCloneException(
          String.format("Cannot get latest revision from repo '%s'", url), e);
    }
  }

  public Optional<Instant> getCommitDate(String repositoryUrl, String revision) {
    Optional<Path> tempDirectory = safelyGetTempDirectory(repositoryUrl, revision);
    if (tempDirectory.isEmpty()) {
      return Optional.empty();
    }

    Optional<Git> gitOpt = cloneSafely(repositoryUrl, tempDirectory.get(), revision);

    if (gitOpt.isEmpty()) {
      return Optional.empty();
    }

    Optional<Instant> instant = gitOpt.map(this::safelyGetLastCommitDate);
    tryRemoveDirectory(tempDirectory.get());
    return instant;
  }

  private void tryRemoveDirectory(Path path) {
    try {
      fileUtils.removeDirectory(path);
    } catch (IOException e) {
      NDCHarvesterLogger.logApplicationError(
          LoggingContext.builder()
              .message("Error removing temp directory")
              .details(e.getMessage())
              .build());
    }
  }

  private Optional<Path> safelyGetTempDirectory(String repositoryUrl, String revision) {
    try {
      return Optional.of(fileUtils.createTempDirectory(TEMP_DIR_PREFIX));
    } catch (IOException e) {
      NDCHarvesterLogger.logApplicationError(
          LoggingContext.builder()
              .message("Error creating temp directory while retrieving commit date")
              .details(e.getMessage())
              .additionalInfo("repositoryUrl", repositoryUrl)
              .additionalInfo("revision", revision)
              .build());
      return Optional.empty();
    }
  }

  private Instant safelyGetLastCommitDate(Git git) {
    try {
      return StreamSupport.stream(git.log().call().spliterator(), false)
          .findFirst()
          .map(RevCommit::getAuthorIdent)
          .map(PersonIdent::getWhenAsInstant)
          .orElse(null);
    } catch (GitAPIException e) {
      NDCHarvesterLogger.logApplicationError(
          LoggingContext.builder()
              .message("Error getting commit date")
              .details(e.getMessage())
              .build());
      return null;
    }
  }

  private Optional<Git> cloneSafely(String repositoryUrl, Path tempDirectory, String revision) {
    try {
      Git git =
          Git.cloneRepository().setURI(repositoryUrl).setDirectory(tempDirectory.toFile()).call();
      git.checkout().setName(revision).call();

      return Optional.of(git);
    } catch (GitAPIException e) {
      return Optional.empty();
    }
  }

  public record ClonedRepoInfo(Path tempDirectory, Instant instant) {}
}
