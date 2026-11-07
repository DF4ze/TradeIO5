package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;

import java.time.Instant;

/** Identité commune d'un {@link TrendPreset} / {@link RainbowPreset} : permet de mutualiser droits, révision et propagation (Espace Expert). */
public interface ExpertPreset {

    Long getId();

    String getAssetSymbol();

    String getReferenceProvider();

    void setReferenceProvider(String referenceProvider);

    String getName();

    void setName(String name);

    PresetOrigin getOrigin();

    User getOwner();

    BasePresetRole getBaseRole();

    int getRevision();

    void setRevision(int revision);

    Instant getCreatedAt();

    Instant getUpdatedAt();

    void setUpdatedAt(Instant updatedAt);
}
