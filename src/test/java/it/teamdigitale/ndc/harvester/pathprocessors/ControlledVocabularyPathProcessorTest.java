package it.teamdigitale.ndc.harvester.pathprocessors;

import it.teamdigitale.ndc.harvester.CsvParser;
import it.teamdigitale.ndc.harvester.model.ControlledVocabularyModel;
import it.teamdigitale.ndc.harvester.model.CvPath;
import it.teamdigitale.ndc.harvester.model.SemanticAssetMetadata;
import it.teamdigitale.ndc.harvester.model.SemanticAssetModelFactory;
import it.teamdigitale.ndc.repository.SemanticAssetMetadataRepository;
import it.teamdigitale.ndc.repository.TripleStoreRepository;
import it.teamdigitale.ndc.service.VocabularyDataService;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlledVocabularyPathProcessorTest {
    @Mock
    SemanticAssetModelFactory semanticAssetModelFactory;
    @Mock
    ControlledVocabularyModel cvModel;
    @Mock
    CsvParser csvParser;
    @Mock
    VocabularyDataService vocabularyDataService;
    @Mock
    TripleStoreRepository tripleStoreRepository;
    @Mock
    SemanticAssetMetadataRepository metadataRepository;
    @Mock
    Model jenaModel;

    String baseUrl = "http://ndc";

    ControlledVocabularyPathProcessor pathProcessor;

    @BeforeEach
    void setup() {
        pathProcessor = new ControlledVocabularyPathProcessor(tripleStoreRepository, semanticAssetModelFactory,
                csvParser, vocabularyDataService, metadataRepository, baseUrl);
    }

    @Test
    void shouldProcessCsv() {
        String ttlFile = "cities.ttl";
        String csvFile = "cities.csv";
        CvPath path = CvPath.of(ttlFile, csvFile);

        when(semanticAssetModelFactory.createControlledVocabulary(ttlFile)).thenReturn(cvModel);
        when(cvModel.getRdfModel()).thenReturn(jenaModel);
        when(cvModel.getKeyConcept()).thenReturn("keyConcept");
        when(cvModel.getRightsHolderId()).thenReturn("rightsHolderId");
        when(csvParser.convertCsvToMapList(csvFile)).thenReturn(List.of(Map.of("key", "val")));
        SemanticAssetMetadata metadata = SemanticAssetMetadata.builder().build();
        when(cvModel.extractMetadata()).thenReturn(metadata);

        pathProcessor.process("some-repo", path);

        verify(semanticAssetModelFactory).createControlledVocabulary(ttlFile);
        verify(csvParser).convertCsvToMapList(csvFile);
        verify(tripleStoreRepository).save("some-repo", jenaModel);
        verify(vocabularyDataService).indexData("rightsHolderId", "keyConcept", List.of(Map.of("key", "val")));
        verify(cvModel).extractMetadata();
        verify(metadataRepository).save(metadata);
    }

    @Test
    void shouldNotAttemptToProcessCsvIfOnlyTtlIsInPath() {
        String ttlFile = "cities.ttl";
        CvPath path = CvPath.of(ttlFile, null);

        when(semanticAssetModelFactory.createControlledVocabulary(ttlFile)).thenReturn(cvModel);
        when(cvModel.getRdfModel()).thenReturn(jenaModel);
        SemanticAssetMetadata metadata = SemanticAssetMetadata.builder().build();
        when(cvModel.extractMetadata()).thenReturn(metadata);

        pathProcessor.process("some-repo", path);

        verify(semanticAssetModelFactory).createControlledVocabulary(ttlFile);
        verify(tripleStoreRepository).save("some-repo", jenaModel);
        verify(cvModel).extractMetadata();

        verify(cvModel, never()).getRightsHolderId();
        verify(cvModel, never()).getKeyConcept();
        verify(metadataRepository).save(metadata);
        verifyNoInteractions(csvParser);
        verifyNoInteractions(vocabularyDataService);
    }

    @Test
    void shouldAddNdcEndpointUrlToModelBeforePersisting() {
        pathProcessor.enrichModelBeforePersisting(cvModel);

        verify(cvModel).addNdcUrlProperty(baseUrl);
    }
}