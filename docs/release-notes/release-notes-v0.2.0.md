# sparrow-joinstr v0.2.0

This release makes joinstr in Sparrow interoperable with the Electrum joinstr plugin. **0.1.1 could not complete a coinjoin with a plugin peer in either direction**, for four independent reasons, all fixed here. It also adds aut-ct sybil resistance and ring signature DoS recovery.

## Interoperability

A pool created in 0.1.1 was invisible to plugin peers, and a plugin pool could not be joined. Fixed:

- Pool announcements are signed by the pool key they advertise. Previously a separate throwaway key signed them, and every other client drops such an announcement.
- Credentials carry every field a joiner checks, with the types the announcement used. `denomination` and `fee_rate` were missing entirely and `id` held the pubkey.
- Fractional fee rates parse. A pool advertising `2.5 sat/vB`, which is normal, was silently dropped.
- The per output fee is estimated at 100 vB per peer, matching the reference implementation. At 150 the two clients derived different output amounts and rejected each other's PSBTs.
- Coinjoin outputs are ordered by announcement time rather than sorted, so peers agree on the transaction they sign.

**Pools created by 0.1.1 cannot be joined from 0.2.0, and vice versa.** Finish or abandon any pool created on 0.1.1 before upgrading.

## Privacy and safety

- A joiner accepted pool credentials from anyone who answered its join request. Since nip 04 does not authenticate senders and the request is public on the relay, an observer could answer first and pull the joiner into a pool it controlled. Credentials must now carry the private key of the pool that was chosen and repeat its advertised terms.
- Requests are refused when Tor is not running instead of going out in the clear. A relay on this machine connects directly, since Tor cannot reach loopback.
- The Tor proxy applies per connection rather than as a JVM wide system property, so the rest of Sparrow's traffic is unaffected.
- The selected input and the registered output are no longer written to the log, where they gave away the linkage a coinjoin exists to break.
- An output address is never handed to two pools.
- `pools.json` is written owner readable only, and a pool's private key is dropped once the pool is finished.
- Pool discovery no longer rotates the Tor circuit under a running coinjoin, which tore down its subscription every 30 seconds and could hang a join.

## Validation before signing and broadcasting

- The selected coin must be confirmed, spendable and not on one of the pool's own output addresses, its output must clear dust, and the coinjoin may not cost more than 15000 sats.
- A peer's registration PSBT must be a single signed input with a witness UTXO, the right sighash, and the expected outputs.
- Before broadcast: no input address reused as an output, no two inputs from one address, inputs exceeding outputs, and a sane fee.

## New

- **aut-ct sybil resistance.** A pool can require each peer to prove it owns a Taproot UTXO in a published keyset without revealing which. Prove with a coin from a Taproot wallet, or with a Taproot WIF for wallets that have none. Set the server under Settings.
- **Ring signature recovery.** When a pool loses a peer during input registration, the peers that registered re-register under a linkable ring signature and rebuild the transaction, instead of the pool being griefed for free.
- The pool creator sets the fee rate and timeout; the fee rate defaults to Sparrow's own estimate.
- The relay in Settings is now actually used.
- Pools that cannot be joined are listed with the reason rather than failing silently.
- A refusal from a pool creator is shown instead of the join hanging until timeout.

## Notes

- Requires Tor. Requests are refused rather than downgraded.
- aut-ct needs an external `autct` server; this release does not manage one for you.
- The UTXO picker lists only confirmed, spendable coins.
