"use client"

import React, { useState } from "react"
import { useRouter } from "next/navigation"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { useAuth } from "@/lib/auth-context"
import { apiClient } from "@/lib/api-client"
import { toast } from "sonner"

export function LoginForm({
  className,
  ...props
}: React.ComponentProps<"div">) {
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [usernameError, setUsernameError] = useState("")
  const [passwordError, setPasswordError] = useState("")
  const [isLoading, setIsLoading] = useState(false)
  const { login } = useAuth()
  const router = useRouter()

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    let valid = true
    setUsernameError("")
    setPasswordError("")

    if (!username.trim()) {
      setUsernameError("Username is required")
      valid = false
    }
    if (!password.trim()) {
      setPasswordError("Password is required")
      valid = false
    }

    if (!valid) return

    setIsLoading(true)

    try {
      const data = await apiClient("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      })

      login(data.token, data.refreshToken, data.user, data.mustChangePassword)

      if (data.mustChangePassword) {
        toast.warning("You must change your password before continuing.")
        router.push("/dashboard/profile")
      } else {
        toast.success("Welcome back!")
        router.push("/dashboard")
      }
    } catch (error: any) {
      setPasswordError(error.message || "Invalid credentials")
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className={cn("flex flex-col gap-6", className)} {...props}>
      <Card className="overflow-hidden p-0">
        <CardContent className="grid p-0 md:grid-cols-2">
          <form noValidate className="p-6 md:p-8" onSubmit={handleSubmit}>
            <FieldGroup>
              <div className="flex flex-col items-center gap-2 text-center">
                <h1 className="text-2xl font-bold">Welcome back</h1>
                <p className="text-balance text-muted-foreground">
                  Login to your account
                </p>
              </div>
              <Field>
                <FieldLabel htmlFor="username">Username</FieldLabel>
                <Input
                  id="username"
                  type="text"
                  placeholder="admin"
                  value={username}
                  onChange={(e) => {
                    setUsername(e.target.value)
                    if (e.target.value.trim()) setUsernameError("")
                  }}
                  className={usernameError ? "border-destructive focus-visible:ring-destructive" : ""}
                />
                {usernameError && <p className="text-sm font-medium text-destructive mt-1">{usernameError}</p>}
              </Field>
              <Field>
                <div className="flex items-center">
                  <FieldLabel htmlFor="password">Password</FieldLabel>
                </div>
                <Input
                  id="password"
                  type="password"
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value)
                    if (e.target.value.trim()) setPasswordError("")
                  }}
                  className={passwordError ? "border-destructive focus-visible:ring-destructive" : ""}
                />
                {passwordError && <p className="text-sm font-medium text-destructive mt-1">{passwordError}</p>}
              </Field>
              <Field>
                <Button type="submit" className="w-full" disabled={isLoading}>
                  {isLoading ? "Signing in..." : "Sign in"}
                </Button>
              </Field>
              <FieldDescription className="text-center text-xs text-muted-foreground">
                Contact your administrator if you have trouble logging in.
              </FieldDescription>
            </FieldGroup>
          </form>
          <div className="relative hidden bg-gradient-to-br from-indigo-900 via-indigo-800 to-slate-900 md:flex flex-col items-center justify-center gap-6 p-10">
            <div className="relative z-10 flex flex-col items-center gap-4 text-center">
              <div className="flex h-24 w-24 items-center justify-center rounded-2xl bg-white shadow-xl overflow-hidden p-3 transition-transform hover:scale-105">
                <img src="/sudani-logo.png" alt="Sudani Logo" className="h-full w-full object-contain" />
              </div>
              <h2 className="text-3xl font-bold text-white tracking-widest mt-2 uppercase">DS Management System</h2>
              <p className="text-indigo-200 text-sm max-w-xs">
                Management & Monitoring Platform
              </p>
              <p className="text-indigo-300 text-xs mt-4 uppercase font-bold tracking-widest">
                Powered by SUDANI
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
