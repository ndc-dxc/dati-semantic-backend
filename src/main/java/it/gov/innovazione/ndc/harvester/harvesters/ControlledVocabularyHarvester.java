package it.gov.innovazione.ndc.harvester.harvesters;

import it.gov.innovazione.ndc.eventhandler.NdcEventPublisher;
import it.gov.innovazione.ndc.eventhandler.event.ConfigService;
import it.gov.innovazione.ndc.harvester.AgencyRepositoryService;
import it.gov.innovazione.ndc.harvester.SemanticAssetType;
import it.gov.innovazione.ndc.harvester.model.CvPath;
import it.gov.innovazione.ndc.harvester.model.HarvesterStatsHolder;
import it.gov.innovazione.ndc.harvester.model.Instance;
import it.gov.innovazione.ndc.harvester.pathprocessors.ControlledVocabularyPathProcessor;
import it.gov.innovazione.ndc.harvester.service.SemanticContentStatsService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class ControlledVocabularyHarvester extends BaseSemanticAssetHarvester<CvPath> {
    private final AgencyRepositoryService agencyRepositoryService;
    private final ControlledVocabularyPathProcessor pathProcessor;

    public ControlledVocabularyHarvester(
            AgencyRepositoryService agencyRepositoryService,
            ControlledVocabularyPathProcessor pathProcessor,
            NdcEventPublisher ndcEventPublisher,
            ConfigService configService,
            SemanticContentStatsService semanticContentStatsService) {
        super(SemanticAssetType.CONTROLLED_VOCABULARY, ndcEventPublisher, configService, semanticContentStatsService);
        this.agencyRepositoryService = agencyRepositoryService;
        this.pathProcessor = pathProcessor;
    }

    @Override
    protected HarvesterStatsHolder processPath(String repoUrl, CvPath path) {
        return pathProcessor.process(repoUrl, path);
    }

    @Override
    protected List<CvPath> scanForPaths(Path rootPath) {
        return agencyRepositoryService.getControlledVocabularyPaths(rootPath);
    }

    @Override
    public void cleanUpBeforeHarvesting(String repoUrl, Instance instance) {
        pathProcessor.dropCsvIndicesForRepo(repoUrl, instance);
    }
}
