import Link from "next/link"
import { Button } from "@/components/ui/button"

export default function NotFound() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-6 text-center p-6 bg-background">
      <div className="flex flex-col gap-2">
        <p className="text-8xl font-bold text-muted-foreground/30">404</p>
        <h1 className="text-3xl font-bold">Page not found</h1>
        <p className="text-muted-foreground max-w-md">
          The page you are looking for doesn&apos;t exist or has been moved.
        </p>
      </div>
      <Button asChild>
        <Link href="/dashboard">Back to Dashboard</Link>
      </Button>
    </div>
  )
}
