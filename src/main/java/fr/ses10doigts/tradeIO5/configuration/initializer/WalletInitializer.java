package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Order(45)
public class WalletInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(WalletInitializer.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final ProviderRepository providerRepository;
    private final ApiCredentialRepository credentialRepository;

    @Override
    public void run(String... args) {

        Optional<User> userOpt = userRepository.findByUsername("OKlm");
        if( userOpt.isEmpty() )
            throw new RuntimeException("Base user OKlm isn't set");

        Optional<WebProvider> exchangeBinanceTestNetOpt = providerRepository.findByCode(WebProviderCode.BINANCE_TESTNET);
        Optional<WebProvider> exchangeBinanceOpt = providerRepository.findByCode(WebProviderCode.BINANCE);
        Optional<WebProvider> exchangeKrakenOpt = providerRepository.findByCode(WebProviderCode.KRAKEN);

        if( exchangeBinanceOpt.isEmpty() || exchangeKrakenOpt.isEmpty() || exchangeBinanceTestNetOpt.isEmpty() )
            throw new RuntimeException("Missing Exchange...");

        User user = userOpt.get();
        WebProvider bnb = exchangeBinanceOpt.get();
        WebProvider bnb_tst = exchangeBinanceTestNetOpt.get();
        WebProvider krk = exchangeKrakenOpt.get();

        LocalDateTime now = LocalDateTime.now();
        List<ApiCredential> credentials = credentialRepository.findByUserAndEnabledTrue(user);
        ApiCredential credBnb = null;
        ApiCredential credBnb_tst = null;
        ApiCredential credKrk = null;

        for (ApiCredential credential : credentials){
            switch (credential.getWebProvider().getCode() ){
                case BINANCE :            credBnb = credential; break;
                case BINANCE_TESTNET :    credBnb_tst = credential; break;
                case KRAKEN :             credKrk = credential; break;
                default: logger.warn("Credential unaffected : {}", credential.getWebProvider().getName());
            };
        }

        Optional<Wallet> binance = walletRepository.findByUserAndName(user, "Binance");
        if( binance.isEmpty() ) {
            Wallet binanceWallet = Wallet.builder()
                    .name("Binance")
                    .enabled(true)
                    .source(WalletSource.EXCHANGE)
                    .webProviderCode(WebProviderCode.BINANCE)
                    .description("Real Binance account!")
                    .webProvider(bnb)
                    .user(user)
                    .creationDate(now)
                    .credential(credBnb)
                    .build();
            walletRepository.save(binanceWallet);
        }


        Optional<Wallet> binanceTst = walletRepository.findByUserAndName(user, "Binance Test");
        if( binanceTst.isEmpty() ) {
            Wallet binanceTestWallet = Wallet.builder()
                    .name("Binance Test")
                    .enabled(false)
                    .source(WalletSource.EXCHANGE)
                    .webProviderCode(WebProviderCode.BINANCE_TESTNET)
                    .description("Binance test account")
                    .webProvider(bnb_tst)
                    .user(user)
                    .creationDate(now)
                    .credential(credBnb_tst)
                    .build();
            walletRepository.save(binanceTestWallet);
        }

        Optional<Wallet> kraken = walletRepository.findByUserAndName(user, "Kraken");
        if( kraken.isEmpty() ) {
            Wallet krakenWallet = Wallet.builder()
                    .name("Kraken")
                    .enabled(true)
                    .source(WalletSource.EXCHANGE)
                    .webProviderCode(WebProviderCode.KRAKEN)
                    .description("Real Kraken account")
                    .webProvider(krk)
                    .user(user)
                    .creationDate(now)
                    .credential(credKrk)
                    .build();
            walletRepository.save(krakenWallet);
        }

        Optional<Wallet> ledger = walletRepository.findByUserAndName(user, "Ledger");
        if( ledger.isEmpty() ) {
            Wallet ledgerWallet = Wallet.builder()
                    .name("Ledger")
                    .enabled(false)
                    .source(WalletSource.NON_CUSTODIAL)
                    .webProviderCode(WebProviderCode.LEDGER)
                    .user(user)
                    .creationDate(now)
                    .build();
            walletRepository.save(ledgerWallet);
        }
    }
}