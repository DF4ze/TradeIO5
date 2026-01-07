package fr.ses10doigts.tradeIO5.service;

import java.util.List;
import java.util.Optional;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final IAuthenticationFacade authenticationFacade;

    public List<Wallet> getAllActiveWallets(){
        return walletRepository.findByEnabledTrue();
    }

    public List<Wallet> getWalletsByUser(User user) {
        return walletRepository.findByUserAndEnabledTrue(user);
    }

    public List<Wallet> getWalletsForCurrentUser() {
        User user = authenticationFacade.getConnectedUser();
        List<Wallet> wallets;
        if( user != null )
            wallets = getWalletsByUser(user);
        else
            wallets = getAllActiveWallets();

        return wallets;
    }
    public Optional<Wallet> getWalletById(Long id) {
        return walletRepository.findById(id);
    }

    public Wallet saveWallet(Wallet wallet) {
        return walletRepository.save(wallet);
    }

    public void deleteWallet(Long id) {
        walletRepository.deleteById(id);
    }


}