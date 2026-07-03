import "./globals.css";

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
    <html lang="en" data-scroll-behavior="smooth">
      <body>{children}</body>
    </html>
  );
}
