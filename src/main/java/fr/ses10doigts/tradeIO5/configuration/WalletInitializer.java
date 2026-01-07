package fr.ses10doigts.tradeIO5.configuration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;

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
        Optional<Provider> exchangeBinanceTestNetOpt = providerRepository.findByCode(ProviderCode.BINANCE_TESTNET);
        Optional<Provider> exchangeBinanceOpt = providerRepository.findByCode(ProviderCode.BINANCE);
        Optional<Provider> exchangeKrakenOpt = providerRepository.findByCode(ProviderCode.KRAKEN);

        if( userOpt.isEmpty() )
            throw new RuntimeException("Base user OKlm isn't set");

        if( exchangeBinanceOpt.isEmpty() || exchangeKrakenOpt.isEmpty() || exchangeBinanceTestNetOpt.isEmpty() )
            throw new RuntimeException("Missing Exchange...");

        User user = userOpt.get();
        Provider bnb = exchangeBinanceOpt.get();
        Provider bnb_tst = exchangeBinanceTestNetOpt.get();
        Provider krk = exchangeKrakenOpt.get();

        LocalDateTime now = LocalDateTime.now();
        List<ApiCredential> credentials = credentialRepository.findByUserAndEnabledTrue(user);
        ApiCredential credBnb = null;
        ApiCredential credBnb_tst = null;
        ApiCredential credKrk = null;

        for (ApiCredential credential : credentials){
            switch (credential.getProvider().getCode() ){
                case BINANCE :            credBnb = credential; break;
                case BINANCE_TESTNET :    credBnb_tst = credential; break;
                case KRAKEN :             credKrk = credential; break;
                default: logger.warn("Credential unaffected : {}", credential.getProvider().getName());
            };
        }

        Optional<Wallet> binance = walletRepository.findByUserAndName(user, "Binance");
        if( binance.isEmpty() ) {
            Wallet binanceWallet = Wallet.builder()
                    .name("Binance")
                    .enabled(true)
                    .source(WalletSource.EXCHANGE)
                    .providerCode(ProviderCode.BINANCE)
                    .description("Real Binance account!")
                    .provider(bnb)
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
                    .providerCode(ProviderCode.BINANCE_TESTNET)
                    .description("Binance test account")
                    .provider(bnb_tst)
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
                    .providerCode(ProviderCode.KRAKEN)
                    .description("Real Kraken account")
                    .provider(krk)
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
                    .providerCode(ProviderCode.LEDGER)
                    .user(user)
                    .creationDate(now)
                    .build();
            walletRepository.save(ledgerWallet);
        }
    }
}