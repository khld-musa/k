import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";
import { cn } from "@/lib/utils";

import { AuthProvider } from "@/lib/auth-context";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ThemeProvider } from "next-themes";
import { Toaster } from "sonner";

// Self-hosted (not fetched from Google Fonts at build time) so the build doesn't depend on
// internet/DNS access to fonts.googleapis.com - see frontend/app/fonts/README.md for provenance.
const jetbrainsMono = localFont({
  src: "./fonts/JetBrainsMono-Variable.woff2",
  weight: "100 800",
  variable: "--font-mono",
  display: "swap",
});
const roboto = localFont({
  src: "./fonts/Roboto-Variable.woff2",
  weight: "100 900",
  variable: "--font-roboto",
  display: "swap",
});

export const metadata: Metadata = {
  title: "ITDS",
  description: "Management & Monitoring Platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={cn("font-sans", roboto.variable, jetbrainsMono.variable)} suppressHydrationWarning>
      <body className="antialiased">
        <ThemeProvider attribute="class" defaultTheme="dark" enableSystem>
          <AuthProvider>
            <TooltipProvider>
              {children}
            </TooltipProvider>
          </AuthProvider>
          <Toaster richColors position="top-right" />
        </ThemeProvider>
      </body>
    </html>
  );
}
