import type { Metadata, Viewport } from "next";
import { Anton, Instrument_Sans, JetBrains_Mono } from "next/font/google";
import { AuthProvider } from "@/lib/auth";
import { Header } from "@/components/Header";
import "./globals.css";

const display = Anton({ weight: "400", subsets: ["latin"], variable: "--font-display" });
const body = Instrument_Sans({ subsets: ["latin"], variable: "--font-body" });
const mono = JetBrains_Mono({ weight: ["500", "700"], subsets: ["latin"], variable: "--font-mono" });

export const metadata: Metadata = {
  title: { default: "Seatline", template: "%s · Seatline" },
  description: "Tickets for sold-out shows, sold fairly and never twice.",
};

export const viewport: Viewport = {
  themeColor: "#141210",
  width: "device-width",
  initialScale: 1,
};

// runs before paint so a saved light theme doesn't flash dark first
const themeScript = `try{var t=localStorage.getItem("seatline.theme");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${display.variable} ${body.variable} ${mono.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body>
        <a href="#main" className="skip">
          Skip to content
        </a>
        <AuthProvider>
          <Header />
          <main id="main">{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
