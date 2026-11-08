import Link from "next/link";
import s from "./not-found.module.css";

export default function NotFound() {
  return (
    <div className={s.shell}>
      <div className={s.stub}>
        <p className="mono eyebrow" style={{ margin: 0 }}>
          ERROR 404 · NO SUCH SEAT
        </p>
        <h1 className={`display ${s.title}`}>This row doesn&apos;t exist.</h1>
        <p className={s.say}>The page you were looking for isn&apos;t here. It may have moved, or the link was mistyped.</p>
        <div className={s.actions}>
          <Link href="/" className="btn btn-primary">
            Back to events
          </Link>
          <Link href="/tickets" className="btn">
            My tickets
          </Link>
        </div>
      </div>
    </div>
  );
}
