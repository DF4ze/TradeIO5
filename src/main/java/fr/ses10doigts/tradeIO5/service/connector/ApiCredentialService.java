package fr.ses10doigts.tradeIO5.service.connector;


import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApiCredentialService {

    private final ApiCredentialRepository apiCredentialRepository;

	public Optional<ApiCredential> getFromProviderAndUser(WebProviderCode code, User user){
		return apiCredentialRepository.findByUserAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(user, code);
    }

}