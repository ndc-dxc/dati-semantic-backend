package it.teamdigitale.ndc.harvester.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class SemanticAssetPath {
    protected final String ttlPath;

    public SemanticAssetPath(String ttlPath) {
        this.ttlPath = ttlPath;
    }
}