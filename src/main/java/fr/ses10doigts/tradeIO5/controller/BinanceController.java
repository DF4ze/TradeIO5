package fr.ses10doigts.tradeIO5.controller;

import java.math.BigDecimal;
import java.util.Optional;

import fr.ses10doigts.tradeIO5.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.connector.ApiCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.ExchangeApiService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/binance")
@RequiredArgsConstructor
public class BinanceController {
/*
    private static final Logger logger = LoggerFactory.getLogger(BinanceController.class);
	private static final String EXCHANGE_CODE = "BINANCE_TESTNET";

    private final ExchangeApiService exchangeApiService;
	private final ApiCredentialService apiCredentialService;

	@GetMapping("/price/{symbol}")
	public ResponseEntity<BigDecimal> getMarketPrice(@PathVariable String symbol) {

        ApiCredential credential = null;
        try {
            credential = apiCredentialService.getFromCurrentUser(EXCHANGE_CODE);
        } catch (NotFoundException e) {
            logger.error("No enabled credential found for current user and exchange {}", EXCHANGE_CODE);
            return ResponseEntity.of(Optional.empty());
        }
        BigDecimal price = exchangeApiService
				.getClient(credential.getExchange().getCode()).getMarketPrice(symbol, "USDC", credential);

        logger.debug("{} : {} USDC", symbol, price);

        return ResponseEntity.ok(price);
    }

    // 🔹 Solde d’un token (ex: BTC) pour l'utilisateur OKlm
    @GetMapping("/balance/{asset}")
    public ResponseEntity<BigDecimal> getUserBalance(@PathVariable String asset) {

        ApiCredential credential = null;
        try {
            credential = apiCredentialService.getFromCurrentUser(EXCHANGE_CODE);
        } catch (NotFoundException e) {
            logger.error("No enabled credential found for current user and exchange {}", EXCHANGE_CODE);
            return ResponseEntity.of(Optional.empty());
        }

        BigDecimal balance = exchangeApiService
				.getUserBalance(credential.getUser(), credential.getExchange().getCode(), asset);

		logger.debug("User " + credential.getUser().getUsername() + " has " + balance + " " + asset);

        return ResponseEntity.ok(balance);
    }
    */

}
