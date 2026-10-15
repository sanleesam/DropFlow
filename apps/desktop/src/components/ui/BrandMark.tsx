import logo from "../../assets/logo.png";

/** Decorative brand artwork; adjacent text supplies the accessible app name. */
export function BrandMark({ className = "df-mark" }: { className?: string }) {
  return (
    <img className={className} src={logo} alt="" aria-hidden="true" draggable={false} />
  );
}
