import { useCallback, useEffect, useImperativeHandle, useRef, useState, forwardRef } from 'react';

/**
 * Touch/mouse signature canvas for the counter tablet.
 *
 * Drawn at the device pixel ratio so a stylus line is not a blurry staircase
 * on a high-DPI tablet, and exported as a PNG data URL — the shape the API's
 * `signature` field expects.
 */
const SignaturePad = forwardRef(function SignaturePad({ height = 190, onChange }, ref) {
  const canvasRef = useRef(null);
  const drawingRef = useRef(false);
  const lastPointRef = useRef(null);
  const [hasInk, setHasInk] = useState(false);

  const resize = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth;

    // Resizing clears the bitmap, so anything already drawn is preserved and
    // painted back afterwards.
    const previous = hasInk ? canvas.toDataURL() : null;

    canvas.width = width * ratio;
    canvas.height = height * ratio;

    const ctx = canvas.getContext('2d');
    ctx.scale(ratio, ratio);
    ctx.lineWidth = 2.4;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = '#16202e';

    if (previous) {
      const image = new Image();
      image.onload = () => ctx.drawImage(image, 0, 0, width, height);
      image.src = previous;
    }
  }, [height, hasInk]);

  useEffect(() => {
    resize();
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
    // Only re-bind on height changes; `resize` itself closes over hasInk.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [height]);

  const pointFrom = (event) => {
    const rect = canvasRef.current.getBoundingClientRect();
    const source = event.touches?.[0] ?? event;
    return { x: source.clientX - rect.left, y: source.clientY - rect.top };
  };

  const start = (event) => {
    event.preventDefault();
    drawingRef.current = true;
    lastPointRef.current = pointFrom(event);
  };

  const move = (event) => {
    if (!drawingRef.current) return;
    event.preventDefault();

    const ctx = canvasRef.current.getContext('2d');
    const point = pointFrom(event);
    const last = lastPointRef.current;

    ctx.beginPath();
    ctx.moveTo(last.x, last.y);
    ctx.lineTo(point.x, point.y);
    ctx.stroke();

    lastPointRef.current = point;
    if (!hasInk) {
      setHasInk(true);
      onChange?.(true);
    }
  };

  const end = () => {
    drawingRef.current = false;
    lastPointRef.current = null;
  };

  const clear = useCallback(() => {
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.restore();
    setHasInk(false);
    onChange?.(false);
  }, [onChange]);

  useImperativeHandle(ref, () => ({
    clear,
    isEmpty: () => !hasInk,
    /** PNG data URL, or null when nothing has been drawn. */
    toDataURL: () => (hasInk ? canvasRef.current.toDataURL('image/png') : null),
  }), [clear, hasInk]);

  return (
    <div>
      <canvas
        ref={canvasRef}
        className={`signature-pad ${hasInk ? 'has-ink' : ''}`}
        style={{ height }}
        onMouseDown={start}
        onMouseMove={move}
        onMouseUp={end}
        onMouseLeave={end}
        onTouchStart={start}
        onTouchMove={move}
        onTouchEnd={end}
        aria-label="Signature area"
      />
      <div className="row" style={{ marginTop: '0.5rem' }}>
        <span className="faint">{hasInk ? 'Signature captured.' : 'Sign above with a finger or stylus.'}</span>
        <button type="button" className="btn-ghost btn-sm row-end" onClick={clear} disabled={!hasInk}>
          Clear
        </button>
      </div>
    </div>
  );
});

export default SignaturePad;
