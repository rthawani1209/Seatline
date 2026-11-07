"use client";

import { useEffect, useRef } from "react";
import s from "./Dialog.module.css";

/** A modal on the native <dialog>, so focus trapping and Escape come from the browser. */
export function Dialog({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  return (
    <dialog ref={ref} className={s.dialog} onClose={onClose} aria-labelledby="dialog-title">
      <div className={s.head}>
        <h2 id="dialog-title" className="display">
          {title}
        </h2>
        <button type="button" className={s.x} onClick={onClose} aria-label="Close">
          ×
        </button>
      </div>
      <div className={s.body}>{children}</div>
    </dialog>
  );
}
