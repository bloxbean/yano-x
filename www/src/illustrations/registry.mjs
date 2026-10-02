// Every illustration a page can request with `<!-- illustration: <id> -->`.
// Add a data module under ./data and list it here.

import appLedgerOverview from './data/app-ledger-overview.mjs';
import messageLifecycle from './data/message-lifecycle.mjs';

export const ILLUSTRATIONS = Object.freeze(Object.fromEntries(
  [appLedgerOverview, messageLifecycle].map((data) => [data.id, data]),
));
