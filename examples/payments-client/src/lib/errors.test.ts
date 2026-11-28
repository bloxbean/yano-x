import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { Cancelled, describeCode, errorMessage } from './errors';

describe('readable errors', () => {
  it('explains ledger and API codes and keeps the code', () => {
    expect(describeCode('SESSION_LIMIT')).toMatch(/limit/);
    expect(errorMessage(new ApiError(409, 'VAULT_NOT_ACTIVE', 'not yet'))).toMatch(/activation slot.*\(VAULT_NOT_ACTIVE\)$/);
    expect(errorMessage(new ApiError(503, 'L1_TIP_UNKNOWN', 'x'))).toMatch(/Cardano tip/);
  });

  it('shows an unknown code with the node\'s message', () => {
    expect(errorMessage(new ApiError(400, 'SOMETHING_NEW', 'details here'))).toBe('SOMETHING_NEW: details here');
  });

  it('treats a cancelled review as no error, and reads CIP-30 wallet errors', () => {
    expect(errorMessage(new Cancelled())).toBeNull();
    expect(errorMessage({ code: 2, info: 'user declined' })).toBe('The wallet refused: user declined');
    expect(errorMessage(new Error('plain'))).toBe('plain');
  });
});
