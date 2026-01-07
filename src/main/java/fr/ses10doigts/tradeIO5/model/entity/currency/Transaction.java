package fr.ses10doigts.tradeIO5.model.entity.currency;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transaction", uniqueConstraints = { @UniqueConstraint(name="uk_transaction_ext_id",columnNames = { "external_transaction_id" }) })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

	// Identifiant unique externe du trade (provenant de l'exchange)
	@Column(name = "external_transaction_id", nullable = false, unique = true)
	private String externalTransactionId;

	// Relation avec l'utilisateur propriétaire
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	// Relation avec l'exchange
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "exchange_id", nullable = false)
	private Provider provider; // FIXME : Est nécessaire?

	// L'asset de la position (ex: BTC)
	@Column(name = "asset", nullable = false, length = 20)
	private String asset;

	// Quantité achetée/vendue
	@Column(name = "quantity", nullable = false, precision = 30, scale = 10)
    private BigDecimal quantity;

	// Prix unitaire auquel la position a été ouverte
	@Column(name = "price", nullable = false, precision = 30, scale = 10)
	private BigDecimal price;

	// Date et heure du trade
	@Column(name = "timestamp", nullable = false)
	private LocalDateTime timestamp;

	// Type de position (achat ou vente)
	@Enumerated(EnumType.STRING)
	@Column(name = "trade_side", nullable = false, length = 10)
	private TradeSide side;

	// Frais payés sur la position
	@Column(name = "fee", precision = 30, scale = 10)
	private BigDecimal fee;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "wallet_id", nullable = false)
	private Wallet wallet;

}