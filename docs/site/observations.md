# Bring external evidence into your application

Generic observations let an application use external information through a
shared verification policy. For example, a workflow can wait for a signed
shipment report before deciding what happens next.

The network call happens outside deterministic execution. Members use the same
accepted, certified input when they apply the application rules.

## Follow a delivery report

1. **Request an observation.** The application identifies what it needs and
   binds the request to the relevant order or payment.
2. **Acquire the evidence.** A configured adapter or reporter obtains data
   outside consensus. Sources, identities, bounds, and acceptance rules are
   selected in advance.
3. **Check and certify.** The host verifies the reports and source evidence
   required by the configured policy. A report arriving is not itself a
   finalized observation result.
4. **Apply the result.** The application consumes the finalized observation
   callback and makes its next deterministic transition. Missing or rejected
   evidence must be handled as an explicit outcome.

The [shipment reference](../appchain/shipment-observation-reference.md) shows a
signed Merkle receipt bound to a payment transaction. It combines stable
Cardano payment observations, a generic delivery observation, an external
payment effect, and a separate Cardano settlement observation.

## What the evidence means

A valid source signature and inclusion proof establish what the authorized
attestor committed. They do not prove that a parcel physically arrived or
that a source is honest. The source trust policy remains part of the
application design. Generic observations do not replace Cardano validation.

The [ADA/USD reference](../appchain/ada-usd-observation-reference.md) demonstrates
multiple source reports and deterministic aggregation with synthetic fixtures.
It is a teaching example, not a production price oracle.

## Observations, roles, and effects

| Need | Capability |
| --- | --- |
| Bring supported external evidence into the application | Generic observations |
| Decide who may propose or approve a business action | [Domain roles and approvals](/tutorials/05-domain-role-approvals/) |
| Authorize work in an external system after a transition | [Effects](/concepts/effects/) |

Yano owns the observation protocol, verification, certification, and callbacks.
Yano X provides application examples and domain behavior through the plugin
catalog. Observation profiles and source policies are pinned inputs; follow
the version-matched reference before creating a new application identity.
The examples are preview workflows, not production escrow or oracle products.
