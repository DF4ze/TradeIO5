package fr.ses10doigts.tradeIO5.model.entity.currency;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "wallet",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wallet_user_name", columnNames = {"user_id", "name"})
        })
@FilterDef(name = "enabledFilter", parameters = @ParamDef(name = "isEnabled", type = Boolean.class))
@Filter(name = "enabledFilter", condition = "enabled = :isEnabled")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
    private WebProviderCode webProviderCode;

    @ManyToOne
    @JoinColumn(name = "web_provider_id")
    private WebProvider webProvider;

    @ManyToOne
    @JoinColumn(name = "credential_id")
    private ApiCredential credential;

    @ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

    @Builder.Default
    @Column(nullable = false)
	private LocalDateTime creationDate = LocalDateTime.now();

    @OneToMany(mappedBy = "wallet", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Transaction> transactions = new ArrayList<>();

    // Optionnel : description ou notes
    @Column(length = 255)
    private String description;

    @Builder.Default
    private boolean enabled = true;
}