package it.gov.innovazione.ndc.harvester.model;

import com.github.jsonldjava.shaded.com.google.common.collect.ImmutableList;
import it.gov.innovazione.ndc.harvester.model.extractors.LiteralExtractor;
import it.gov.innovazione.ndc.harvester.model.extractors.NodeExtractor;
import it.gov.innovazione.ndc.harvester.model.extractors.NodeSummaryExtractor;
import it.gov.innovazione.ndc.harvester.model.extractors.RightsHolderExtractor;
import it.gov.innovazione.ndc.harvester.model.index.Distribution;
import it.gov.innovazione.ndc.harvester.model.index.NodeSummary;
import it.gov.innovazione.ndc.harvester.model.index.RightsHolder;
import it.gov.innovazione.ndc.harvester.model.index.SemanticAssetMetadata;
import it.gov.innovazione.ndc.harvester.model.index.SemanticAssetMetadata.Fields;
import it.gov.innovazione.ndc.model.profiles.Admsapit;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static it.gov.innovazione.ndc.harvester.SemanticAssetType.SCHEMA;
import static it.gov.innovazione.ndc.harvester.model.SemanticAssetModelValidationContext.NO_VALIDATION;
import static it.gov.innovazione.ndc.harvester.model.extractors.LiteralExtractor.extract;
import static it.gov.innovazione.ndc.harvester.model.extractors.LiteralExtractor.extractOptional;
import static it.gov.innovazione.ndc.harvester.model.extractors.NodeExtractor.requireNodes;
import static it.gov.innovazione.ndc.harvester.model.extractors.NodeSummaryExtractor.extractRequiredNodeSummary;
import static it.gov.innovazione.ndc.harvester.model.extractors.NodeSummaryExtractor.maybeNodeSummaries;
import static it.gov.innovazione.ndc.harvester.model.index.SemanticAssetMetadata.Fields.keyClasses;
import static it.gov.innovazione.ndc.model.profiles.EuropePublicationVocabulary.FILE_TYPE_JSON;
import static org.apache.jena.vocabulary.DCAT.distribution;
import static org.apache.jena.vocabulary.DCAT.keyword;
import static org.apache.jena.vocabulary.DCAT.theme;
import static org.apache.jena.vocabulary.DCTerms.conformsTo;
import static org.apache.jena.vocabulary.DCTerms.description;
import static org.apache.jena.vocabulary.DCTerms.issued;
import static org.apache.jena.vocabulary.DCTerms.modified;
import static org.apache.jena.vocabulary.DCTerms.rightsHolder;
import static org.apache.jena.vocabulary.DCTerms.title;
import static org.apache.jena.vocabulary.OWL.versionInfo;

public class SchemaModel extends BaseSemanticAssetModel {

    public SchemaModel(Model coreModel, String source, String repoUrl, Instance instance) {
        super(coreModel, source, repoUrl, instance);
    }

    private SchemaModel(Model coreModel, String source, String repoUrl, SemanticAssetModelValidationContext validationContext, Instance instance) {
        super(coreModel, source, repoUrl, validationContext, instance);
    }

    public static SchemaModel forValidation(Model rdfModel, String source, String repoUrl, Instance instance) {
        return new SchemaModel(rdfModel, source, repoUrl, SemanticAssetModelValidationContext.getForValidation(), instance);
    }

    @Override
    protected String getMainResourceTypeIri() {
        return SCHEMA.getTypeIri();
    }

    @Override
    public SemanticAssetMetadata extractMetadata() {
        Resource mainResource = getMainResource();
        RightsHolder rightsHolderObj = RightsHolderExtractor.getAgencyId(mainResource, validationContext);
        return SemanticAssetMetadata.builder()
                .instance(instance.name())
                .iri(mainResource.getURI())
                .repoUrl(repoUrl)
                .title(LiteralExtractor.extract(mainResource, title, validationContext))
                .description(LiteralExtractor.extract(mainResource, description, validationContext))
                .distributions(getDistributions())
                .rightsHolder(NodeSummaryExtractor.extractRequiredNodeSummary(mainResource, rightsHolder, FOAF.name, validationContext))
                .type(SCHEMA)
                .modifiedOn(parseDate(LiteralExtractor.extractOptional(mainResource, modified, validationContext)))
                .themes(asIriList(NodeExtractor.requireNodes(mainResource, theme, validationContext)))
                .issuedOn(parseDate(LiteralExtractor.extractOptional(mainResource, issued, validationContext)))
                .versionInfo(LiteralExtractor.extract(mainResource, versionInfo, validationContext))
                .keywords(LiteralExtractor.extractAll(mainResource, keyword))
                .conformsTo(NodeSummaryExtractor.maybeNodeSummaries(mainResource, conformsTo, FOAF.name, validationContext))
                .keyClasses(getKeyClasses())
                .status(LiteralExtractor.extractAll(mainResource, Admsapit.status))
                .agencyId(rightsHolderObj.getIdentifier())
                .agencyLabel(new ArrayList<>(rightsHolderObj.getName().values()))
                .build();
    }

    @Override
    public SemanticAssetModelValidationContext validateMetadata() {
        return new ImmutableList.Builder<Consumer<SemanticAssetModelValidationContext>>()
                .add(v -> extract(getMainResource(), title, v.error().field(Fields.title)))
                .add(v -> extract(getMainResource(), description, v.error().field(Fields.description)))
                .add(v -> getDistributions(v.error().field(Fields.distributions)))
                .add(v -> extractRequiredNodeSummary(getMainResource(), rightsHolder, FOAF.name, v.error().field(Fields.rightsHolder)))
                .add(v -> extractOptional(getMainResource(), modified, v.warning().field(Fields.modifiedOn)))
                .add(v -> requireNodes(getMainResource(), theme, v.error().field(Fields.themes)))
                .add(v -> extractOptional(getMainResource(), issued, v.warning().field(Fields.issuedOn)))
                .add(v -> extract(getMainResource(), versionInfo, v.error().field(Fields.versionInfo)))
                .add(v -> maybeNodeSummaries(getMainResource(), conformsTo, FOAF.name, v.warning().field(Fields.conformsTo)))
                .add(v -> getKeyClasses(getMainResource(), v.warning().field(keyClasses)))
                .build()
                .stream()
                .map(consumer -> returningValidationContext(this.validationContext, consumer))
                .reduce(SemanticAssetModelValidationContext::merge)
                .orElse(this.validationContext);
    }


    private static List<NodeSummary> getKeyClasses(Resource mainResource, SemanticAssetModelValidationContext validationContext) {
        return NodeSummaryExtractor.maybeNodeSummaries(mainResource, Admsapit.hasKeyClass, RDFS.label, validationContext);
    }

    private List<NodeSummary> getKeyClasses() {
        return getKeyClasses(getMainResource(), NO_VALIDATION);
    }

    @Override
    protected List<Distribution> getDistributions(SemanticAssetModelValidationContext validationContext) {
        return extractDistributionsFilteredByFormat(getMainResource(), distribution, FILE_TYPE_JSON, validationContext);
    }
}
