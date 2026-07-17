import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'

describe('AppShell action exclusivity', () => {
  afterEach(cleanup)

  it('keeps reset unavailable while another lab action owns the session', () => {
    const onReset = vi.fn()
    const props = {
      view: 'live' as const,
      onViewChange: vi.fn(),
      connection: 'connected' as const,
      kafkaHealth: 'healthy' as const,
      foundryHealth: 'healthy' as const,
      streamRate: 12,
      streamPaused: false,
      onToggleStream: vi.fn(),
      onReset,
      resetPending: false,
      resetDisabled: true,
      mobileMenuOpen: false,
      onMobileMenuChange: vi.fn(),
      providerMode: 'simulated' as const,
      transportMode: 'memory' as const,
    }
    const { rerender } = render(<AppShell {...props}><p>Lab</p></AppShell>)

    expect(screen.getByRole('button', { name: 'Reset session' })).toBeDisabled()
    rerender(<AppShell {...props} resetDisabled={false}><p>Lab</p></AppShell>)
    fireEvent.click(screen.getByRole('button', { name: 'Reset session' }))
    expect(onReset).toHaveBeenCalledTimes(1)
  })
})
