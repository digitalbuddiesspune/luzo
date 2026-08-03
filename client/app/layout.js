import { Lilita_One, Manrope } from "next/font/google";
import "./globals.css";

const brandFont = Lilita_One({
  weight: "400",
  subsets: ["latin"],
  variable: "--font-brand",
  display: "swap",
});

const uiFont = Manrope({
  subsets: ["latin"],
  variable: "--font-ui",
  display: "swap",
});

export const metadata = {
  title: "PotLudo",
  description:
    "Realtime Ludo betting demo with wallet pots, public rooms, private rooms, and bot fill support.",
};

export const viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en" data-scroll-behavior="smooth" className={`${brandFont.variable} ${uiFont.variable}`}>
      <body>{children}</body>
    </html>
  );
}
