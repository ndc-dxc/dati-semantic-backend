package it.gov.innovazione.ndc.repository;

import com.apicatalog.jsonld.StringUtils;
import it.gov.innovazione.ndc.service.logging.HarvesterStage;
import it.gov.innovazione.ndc.service.logging.LoggingContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.arq.querybuilder.SelectBuilder;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.update.UpdateExecution;
import org.springframework.stereotype.Repository;

import java.net.URL;

import static it.gov.innovazione.ndc.service.logging.NDCHarvesterLogger.logInfrastructureError;
import static it.gov.innovazione.ndc.service.logging.NDCHarvesterLogger.logSemanticError;
import static it.gov.innovazione.ndc.service.logging.NDCHarvesterLogger.logSemanticInfo;
import static java.lang.String.format;

@Slf4j
@Repository
public class TripleStoreRepository {
    private static final String DROP_SILENT_GRAPH_WITH_LOG_ENABLE_3 = "DEFINE sql:log-enable 3%nDROP SILENT GRAPH <%s>%n";
    public static final String TMP_GRAPH_PREFIX = "tmp";
    public static final String ONLINE_GRAPH_PREFIX = "";
    private static final String RENAME_GRAPH = "DEFINE sql:log-enable 3%nMOVE SILENT GRAPH <%s> to <%s>%n";

    private final VirtuosoClient virtuosoClient;

    public TripleStoreRepository(VirtuosoClient virtuosoClient) {
        this.virtuosoClient = virtuosoClient;
    }

    private static String getCommandAndLog(String command) {
        log.info("Update command: {}", command);
        return command;
    }

    private static String getRenameCommand(String oldGraph, String newGraph) {
        return getCommandAndLog(format(RENAME_GRAPH, oldGraph, newGraph));
    }

    private void saveWithConnection(String graphName, Model model, RDFConnection connection) {
        try {
            connection.load(graphName, model);
        } catch (Exception e) {
            log.error("Could not flush!", e);
            if (e instanceof HttpException) {
                HttpException httpException = (HttpException) e;
                log.error("HttpException: {}", httpException.getResponse());
            }
            throw new TripleStoreRepositoryException(format("Could not save model to '%s'", graphName), e);
        }
    }

    private static String getUpdateCommand(String repoUrl, String repoUrlPrefix) {
        return getCommandAndLog(format(DROP_SILENT_GRAPH_WITH_LOG_ENABLE_3, reworkRepoUrlIfNecessary(repoUrl, repoUrlPrefix)));
    }

    @SneakyThrows
    private static String reworkRepoUrlIfNecessary(String repoUrl, String repoUrlPrefix) {
        if (StringUtils.isBlank(repoUrlPrefix)) {
            return repoUrl;
        }
        URL url = new URL(repoUrl);
        String port = url.getPort() == -1 ? "" : ":" + url.getPort();
        return url.getProtocol() + "://" + repoUrlPrefix + "." + url.getHost() + port + url.getPath();
    }

    public void clearExistingNamedGraph(String repoUrl) {
        log.info("Clearing existing named graph for {}", repoUrl);
        clearExistingNamedGraph(repoUrl, ONLINE_GRAPH_PREFIX);
    }

    public void clearExistingNamedGraph(String repoUrl, String prefix) {
        try {
            log.info("Clearing existing named graph for {} with prefix {}", repoUrl, prefix);

            logSemanticInfo(LoggingContext.builder()
                    .stage(HarvesterStage.CLEANING_VIRTUOSO)
                    .message("Clearing existing named graph for repo " + repoUrl)
                    .additionalInfo("prefix", prefix)
                    .build());

            String sparqlEndpoint = virtuosoClient.getSparqlEndpoint();
            UpdateExecution
                    .service(sparqlEndpoint)
                    .updateString(getUpdateCommand(repoUrl, prefix))
                    .execute();
        } catch (Exception e) {
            log.error(format("Could not clear existing named graph! - %s", repoUrl), e);
            boolean isHttpException = e instanceof HttpException;
            if (isHttpException) {
                HttpException httpException = (HttpException) e;
                log.error("HttpException: {}", httpException.getResponse());
            }
            LoggingContext loggingContext = LoggingContext.builder()
                    .stage(HarvesterStage.CLEANING_VIRTUOSO)
                    .message("Could not clear existing named graph for repo " + repoUrl)
                    .details(e.getMessage())
                    .additionalInfo("prefix", prefix)
                    .build();
            if (isHttpException) {
                logInfrastructureError(loggingContext);
            } else {
                logSemanticError(loggingContext);
            }
            throw new TripleStoreRepositoryException(format("Could not delete graph - '%s'", repoUrl), e);
        }
    }

    public void save(String graphName, Model model) {
        log.info("Saving model to Virtuoso");
        try (RDFConnection connection = virtuosoClient.getConnection()) {
            saveWithConnection(reworkRepoUrlIfNecessary(graphName, TMP_GRAPH_PREFIX), model, connection);
        }
        log.info("Model saved to Virtuoso");
    }

    public void switchInstances(it.gov.innovazione.ndc.model.harvester.Repository repository) {
        String tmpGraphName = reworkRepoUrlIfNecessary(repository.getUrl(), TMP_GRAPH_PREFIX);
        log.info("Switching instances on Virtuoso ({}, {})", repository.getUrl(), tmpGraphName);
        clearExistingNamedGraph(repository.getUrl());
        rename(tmpGraphName, repository.getUrl());
    }

    public void rename(String oldGraph, String newGraph) {
        try {
            String sparqlEndpoint = virtuosoClient.getSparqlEndpoint();
            log.info("Renaming {} into {}", oldGraph, newGraph);
            UpdateExecution
                    .service(sparqlEndpoint)
                    .updateString(getRenameCommand(oldGraph, newGraph))
                    .execute();
            log.info("Renamed {} into {}", oldGraph, newGraph);
        } catch (Exception e) {
            log.error(format("Could not rename %s into %s ", oldGraph, newGraph), e);
            throw new TripleStoreRepositoryException(format("Could not rename - '%s' -> '%s'", oldGraph, newGraph), e);
        }
    }

    public QueryExecution select(SelectBuilder selectBuilder) {
        try (RDFConnection connection = virtuosoClient.getConnection()) {
            Query query = selectBuilder.build();
            return connection.query(query);
        } catch (Exception e) {
            log.error(format("Could not execute select! - %s", selectBuilder), e);
            if (e instanceof HttpException) {
                HttpException httpException = (HttpException) e;
                log.error("HttpException: {}", httpException.getResponse());
            }
            throw new TripleStoreRepositoryException(format("Could not execute select - '%s'", selectBuilder), e);
        }
    }

    public void clearTempGraphIfExists(String repoUrl) {
        try {
            log.info("Clearing temp graph for {}", repoUrl);
            clearExistingNamedGraph(repoUrl, TMP_GRAPH_PREFIX);
            log.info("Cleared temp graph for {}", repoUrl);
        } catch (Exception e) {
            log.error(format("Could not clear temp graph for %s", repoUrl), e);
        }
    }
}
