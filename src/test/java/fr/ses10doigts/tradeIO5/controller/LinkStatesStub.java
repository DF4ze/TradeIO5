package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.LinkStateService.LinkState;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link LinkStateService} neutre pour les tests de contrôleurs : aucun lien suspendu ni en attente de validation. */
final class LinkStatesStub {

    private LinkStatesStub() {
    }

    static LinkStateService neutral() {
        LinkStateService states = mock(LinkStateService.class);
        when(states.of(any())).thenReturn(LinkState.NONE);
        return states;
    }
}
