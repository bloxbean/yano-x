package org.yanoproject.x.walletauth;

/** Thrown when a wallet signature, session signature or intent envelope is not acceptable. */
public final class WalletAuthException extends RuntimeException {
    public WalletAuthException(String message) {
        super(message);
    }
}
