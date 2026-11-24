"use client"

import * as React from "react"
import { toast } from "sonner"
import { Loader2, Globe } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from "@/components/ui/card"
import { apiClient } from "@/lib/api-client"

export default function ApiConfigPage() {
  const [url, setUrl] = React.useState("")
  const [error, setError] = React.useState("")
  const [isLoading, setIsLoading] = React.useState(true)
  const [isSaving, setIsSaving] = React.useState(false)

  React.useEffect(() => {
    async function fetchConfig() {
      try {
        const data = await apiClient("/system-settings/sesme.api.url")
        setUrl(data.value || "http://localhost:5000/v1/balance")
      } catch (error) {
        toast.error("Failed to load API configuration")
      } finally {
        setIsLoading(false)
      }
    }
    fetchConfig()
  }, [])

  const validateUrl = (value: string) => {
    try {
      const parsed = new URL(value.trim())
      return parsed.protocol === "http:" || parsed.protocol === "https:"
    } catch (_) {
      return false
    }
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()

    if (!url.trim()) {
      setError("API URL is required.")
      return
    }
    if (!validateUrl(url)) {
      setError("Please enter a valid URL (e.g., http://example.com/api)")
      return
    }

    setError("")
    setIsSaving(true)
    try {
      await apiClient("/system-settings/sesme.api.url", {
        method: "PUT",
        body: JSON.stringify({ value: url, description: "External API URL for current balance" }),
      })

      toast.success("API Configuration updated successfully")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "An error occurred while saving the configuration")
    } finally {
      setIsSaving(false)
    }
  }

  if (isLoading) {
    return (
      <div className="flex h-[400px] w-full items-center justify-center">
        <div className="flex flex-col items-center gap-2">
          <Loader2 className="h-8 w-8 animate-spin text-primary" />
          <p className="text-sm text-muted-foreground">Loading configuration...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 space-y-6 p-4 md:p-8 pt-6">
      <div className="flex items-center justify-between">
        <div className="space-y-1">
          <h2 className="text-3xl font-bold tracking-tight">API Configuration</h2>
          <p className="text-muted-foreground">
            Manage external integration endpoints and system parameters.
          </p>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-7">
        <Card className="col-span-4">
          <CardHeader>
            <CardTitle>Balance Service</CardTitle>
            <CardDescription>
              Configure the endpoint used to retrieve subscriber credit balances.
            </CardDescription>
          </CardHeader>
          <form noValidate onSubmit={handleSave}>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="api-url">Get Balance API URL</Label>
                <div className="relative">
                  <Globe className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
                  <Input
                    id="api-url"
                    className={`pl-9 ${error ? "border-destructive focus-visible:ring-destructive" : ""}`}
                    aria-invalid={!!error}
                    value={url}
                    onChange={(e) => {
                      setUrl(e.target.value)
                      if (error) setError("")
                    }}
                    placeholder="https://api.example.com/v1/balance"
                    disabled={isSaving}
                  />
                </div>
                {error && <p className="text-sm font-medium text-destructive">{error}</p>}
                <p className="text-xs text-muted-foreground">
                  This endpoint will be queried periodically by the balance monitor service to track account credits.
                </p>
              </div>
            </CardContent>
            <CardFooter className="border-t px-6 py-4">
              <Button type="submit" disabled={isSaving}>
                {isSaving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Save Changes
              </Button>
            </CardFooter>
          </form>
        </Card>

        <Card className="col-span-3">
          <CardHeader>
            <CardTitle>Documentation</CardTitle>
          </CardHeader>
          <CardContent className="text-sm space-y-4">
            <div className="rounded-md bg-muted p-4">
              <h4 className="font-semibold mb-2">Endpoint Requirements</h4>
              <ul className="list-disc pl-4 space-y-1 text-muted-foreground">
                <li>Must support HTTP GET requests</li>
                <li>Requires API key and Client ID as parameters</li>
                <li>Response must be in JSON format</li>
                <li>Should be reachable from the DS Management System backend</li>
              </ul>
            </div>

            <div className="space-y-1">
              <p className="font-medium">Latency Advice</p>
              <p className="text-muted-foreground">
                Ensure the external service responds within 5 seconds to avoid timeouts in the balance monitor.
              </p>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
