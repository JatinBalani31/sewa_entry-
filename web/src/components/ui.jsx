import { useEffect } from 'react';
import { SOURCE_LABEL, STATUS_CLASS, STATUS_LABEL } from '../lib/format.js';

export function Alert({ kind = 'info', children, onDismiss }) {
  if (!children) return null;
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div className="row">
        <span style={{ flex: 1 }}>{children}</span>
        {onDismiss && (
          <button type="button" className="btn-ghost btn-sm" onClick={onDismiss} aria-label="Dismiss">✕</button>
        )}
      </div>
    </div>
  );
}

export function StatusBadge({ status }) {
  return <span className={`badge ${STATUS_CLASS[status] || 'badge-absent'}`}>{STATUS_LABEL[status] || status}</span>;
}

export function SourceBadge({ source }) {
  if (!source) return <span className="faint">—</span>;
  return (
    <span className={`badge ${source === 'ADMIN' ? 'badge-admin' : 'badge-neutral'}`}>
      {SOURCE_LABEL[source] || source}
    </span>
  );
}

export function Spinner({ label }) {
  return (
    <span className="row" style={{ gap: '0.5rem' }}>
      <span className="spinner" aria-hidden="true" />
      {label && <span className="muted">{label}</span>}
    </span>
  );
}

export function LoadingScreen({ label = 'Loading…' }) {
  return (
    <div className="loading-screen">
      <span className="spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function EmptyRow({ colSpan, children }) {
  return (
    <tr>
      <td colSpan={colSpan} data-label="">
        <div className="table-empty">{children}</div>
      </td>
    </tr>
  );
}

export function Modal({ title, subtitle, onClose, children }) {
  // Escape closes the sheet, and the page behind it must not scroll while it
  // is open — especially on a phone, where the modal is a bottom sheet.
  useEffect(() => {
    const onKey = (event) => event.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = previous;
    };
  }, [onClose]);

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => event.target === event.currentTarget && onClose()}
      role="presentation"
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {subtitle && <p className="modal-sub">{subtitle}</p>}
        {children}
      </div>
    </div>
  );
}

export function Stat({ label, value, note, tone }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={tone ? { color: `var(--${tone})` } : undefined}>{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}

/** Horizontal scroller of choices — the phone-friendly stand-in for a segmented control. */
export function ChipRow({ options, value, onChange }) {
  return (
    <div className="chip-row">
      {options.map((option) => (
        <button
          key={option.value ?? 'all'}
          type="button"
          className={`chip ${String(option.value ?? '') === String(value ?? '') ? 'active' : ''}`}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
