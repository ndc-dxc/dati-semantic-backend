package it.gov.innovazione.ndc.repository;

import it.gov.innovazione.ndc.harvester.model.Instance;
import it.gov.innovazione.ndc.harvester.model.index.SemanticAssetMetadata;
import it.gov.innovazione.ndc.service.InstanceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchPage;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Repository;
import org.springframework.util.ObjectUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static it.gov.innovazione.ndc.harvester.SemanticAssetType.CONTROLLED_VOCABULARY;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.springframework.data.elasticsearch.core.SearchHitSupport.searchPageFor;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SemanticAssetMetadataRepository {
    private final ElasticsearchOperations esOps;
    private final InstanceManager instanceManager;

    public SearchPage<SemanticAssetMetadata> search(String queryPattern, Set<String> types,
                                                    Set<String> themes, Set<String> rightsHolder,
                                                    Pageable pageable) {
        BoolQueryBuilder boolQuery =
                new BoolQueryBuilder().must(matchQuery("searchableText", queryPattern));

        addFilters(types, themes, rightsHolder, boolQuery);

        BoolQueryBuilder conditionForInstances = getConditionForInstances();

        boolQuery.must(conditionForInstances);

        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(boolQuery)
                .withPageable(pageable)
                .build();
        return searchPageFor(esOps.search(query, SemanticAssetMetadata.class), pageable);
    }

    private BoolQueryBuilder getConditionForInstances() {
        List<Pair<String, Instance>> currentInstances = instanceManager.getInstances();

        BoolQueryBuilder query = new BoolQueryBuilder();

        currentInstances.stream()
                .map(pair -> getBoolQueryForOneRepo(
                        pair.getKey(),
                        pair.getValue()))
                .forEach(query::should);

        return query;
    }

    private BoolQueryBuilder getBoolQueryForOneRepo(String key, Instance value) {
        BoolQueryBuilder query = new BoolQueryBuilder();

        return query.must(termQuery("repoUrl", key))
                .must(termQuery("instance", value.name()));
    }

    public Optional<SemanticAssetMetadata> findByIri(String iri) {
        return Optional.ofNullable(esOps.get(iri, SemanticAssetMetadata.class));
    }

    public long deleteByRepoUrl(String repoUrl, Instance instance) {
        QueryBuilder queryBuilder = boolQuery()
                .must(termQuery("repoUrl", repoUrl))
                .must(termQuery("instance", instance.name()));
        return esOps.delete(new NativeSearchQuery(queryBuilder),
                SemanticAssetMetadata.class).getDeleted();
    }

    public void save(SemanticAssetMetadata metadata) {
        esOps.save(metadata);
    }

    private void addFilters(Set<String> types, Set<String> themes, Set<String> rightsHolder, BoolQueryBuilder finalQuery) {
        if (!types.isEmpty()) {
            finalQuery.filter(new TermsQueryBuilder("type", types));
        }
        if (!themes.isEmpty()) {
            finalQuery.filter(new TermsQueryBuilder("themes", themes));
        }
        if (Objects.nonNull(rightsHolder) && !rightsHolder.isEmpty()) {
            finalQuery.filter(new TermsQueryBuilder("agencyId", rightsHolder));
        }
    }

    private QueryBuilder matchQuery(String field, String value) {
        QueryBuilder textSearch;
        if (ObjectUtils.isEmpty(value)) {
            textSearch = new MatchAllQueryBuilder();
        } else {
            textSearch = new MatchQueryBuilder(field, value)
                    .fuzziness(Fuzziness.AUTO);
        }
        return textSearch;
    }

    public List<SemanticAssetMetadata> findVocabulariesForRepoUrl(String repoUrl, Instance instance) {
        QueryBuilder queryBuilder = boolQuery()
                .must(termQuery("repoUrl", repoUrl))
                .must(termQuery("instance", instance.name()))
                .must(termQuery("type", CONTROLLED_VOCABULARY.name()));
        NativeSearchQuery query = new NativeSearchQueryBuilder().withQuery(queryBuilder).build();
        SearchHits<SemanticAssetMetadata> hits = esOps.search(query, SemanticAssetMetadata.class);
        return hits.get().map(SearchHit::getContent).collect(Collectors.toList());
    }
}
