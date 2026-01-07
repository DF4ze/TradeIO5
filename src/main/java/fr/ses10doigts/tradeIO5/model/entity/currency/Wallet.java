package fr.ses10doigts.tradeIO5.model.entity.currency;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;
import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Entity
@Table(name = "wallet",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wallet_user_name", columnNames = {"user_id", "name"})
        })
@FilterDef(name = "enabledFilter", parameters = @ParamDef(name = "isEnabled", type = Boolean.class))
@Filter(name = "enabledFilter", condition = "enabled = :isEnabled")
@Data
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
	private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WalletSource source;

    // Code interne pour identifier la source spécifique (ex: BINANCE, KRAKEN, LEDGER, METAMASK…)
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ProviderCode providerCode;

    @ManyToOne
    @JoinColumn(name = "exchange_id")
    private Provider provider;

    @ManyToOne
    @JoinColumn(name = "credential_id")
    private ApiCredential credential;

    @ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

    @Column(nullable = false)
	private LocalDateTime creationDate;

    @OneToMany(mappedBy = "wallet", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Transaction> transactions = new ArrayList<>();

    // Optionnel : description ou notes
    @Column(length = 255)
    private String description;

    private boolean enabled = true;

    public Wallet() {
		this.creationDate = LocalDateTime.now();
    }
}