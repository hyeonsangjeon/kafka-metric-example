import { Boxes } from 'lucide-react'

export function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? 'brand--compact' : ''}`} aria-label="Foundry Stream Lab">
      <span className="brand__mark" aria-hidden="true"><Boxes size={22} strokeWidth={1.8} /></span>
      <span className="brand__copy">
        <strong>Foundry</strong>
        <span>Stream Lab</span>
      </span>
    </div>
  )
}
