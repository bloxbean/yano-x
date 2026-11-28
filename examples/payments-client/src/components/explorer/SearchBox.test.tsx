// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../lib/api';
import { ACCOUNT, INFO } from '../../test/fixtures';

const api = {
  message: vi.fn(),
  receipt: vi.fn(),
  block: vi.fn(),
  address: vi.fn(),
};

vi.mock('../../state', () => ({ usePayments: () => ({ api, info: INFO }) }));

const { SearchBox } = await import('./Explorer');

afterEach(cleanup);
beforeEach(() => {
  window.location.hash = '';
  for (const fn of Object.values(api)) fn.mockReset();
});

function search(text: string) {
  fireEvent.change(screen.getByTestId('explorer-search'), { target: { value: text } });
  fireEvent.click(screen.getByTestId('explorer-search-go'));
}

describe('the explorer search box', () => {
  it('opens a block by height without asking the node', async () => {
    render(<SearchBox />);
    search('12');
    await waitFor(() => expect(window.location.hash).toBe('#/explorer/block/12'));
    expect(api.message).not.toHaveBeenCalled();
  });

  it('opens an account by id', async () => {
    render(<SearchBox />);
    search(ACCOUNT.accountId);
    await waitFor(() => expect(window.location.hash).toBe(`#/explorer/account/${ACCOUNT.accountId}`));
  });

  it('opens a transaction by message id', async () => {
    api.message.mockImplementation(async (id: string) => ({ messageId: id }));
    render(<SearchBox />);
    search('ab'.repeat(32));
    await waitFor(() => expect(window.location.hash).toBe(`#/explorer/tx/${'ab'.repeat(32)}`));
  });

  it('explains input it cannot parse, and an id the chain does not have', async () => {
    render(<SearchBox />);
    search('hello');
    expect(screen.getByTestId('search-error').textContent).toMatch(/Not a block height/);
    api.message.mockRejectedValue(new ApiError(404, '404', 'none'));
    api.receipt.mockResolvedValue({ found: false });
    search('cd'.repeat(32));
    await waitFor(() => expect(screen.getByTestId('search-error').textContent).toMatch(/No message or intent/));
    expect(window.location.hash).toBe('');
  });
});
