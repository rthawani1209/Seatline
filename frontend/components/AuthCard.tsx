import s from "./AuthCard.module.css";

export function AuthCard({ kicker, title, children }: { kicker: string; title: string; children: React.ReactNode }) {
  return (
    <div className={s.shell}>
      <div className={s.card}>
        <div className={s.top}>
          <div className="mono" style={{ fontSize: 11, letterSpacing: "0.14em" }}>
            {kicker}
          </div>
          <h1 className="display">{title}</h1>
        </div>
        <div className={s.tear} aria-hidden="true">
          <span />
        </div>
        <div className={s.form}>{children}</div>
      </div>
    </div>
  );
}

export const authStyles = s;
