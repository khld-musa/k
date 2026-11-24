"use client"

import React, { useState } from "react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { toast } from "sonner"
import { UserPlus } from "lucide-react"

interface CreateUserModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess: () => void
}

export function CreateUserModal({ open, onOpenChange, onSuccess }: CreateUserModalProps) {
  // Form states
  const [username, setUsername] = useState("")
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [role, setRole] = useState("USER")

  // Validation error states
  const [errors, setErrors] = useState<{
    username?: string
    email?: string
    password?: string
    general?: string
  }>({})

  const [isSaving, setIsSaving] = useState(false)

  const resetForm = () => {
    setUsername("")
    setEmail("")
    setPassword("")
    setRole("USER")
    setErrors({})
  }

  // Handle dialog close, but we only reset form if it actually closed
  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen && !isSaving) {
      resetForm()
      onOpenChange(false)
    }
    if (newOpen) {
      onOpenChange(true)
    }
  }

  const validateForm = () => {
    const newErrors: typeof errors = {}
    let isValid = true

    // Username validation
    if (!username.trim()) {
      newErrors.username = "Username is required."
      isValid = false
    } else if (username.trim().length < 3 || username.trim().length > 50) {
      newErrors.username = "Username must be between 3 and 50 characters."
      isValid = false
    }

    // Email validation
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!email.trim()) {
      newErrors.email = "Email address is required."
      isValid = false
    } else if (!emailRegex.test(email)) {
      newErrors.email = "Please enter a valid email address."
      isValid = false
    }

    // Password validation
    if (!password) {
      newErrors.password = "Password is required."
      isValid = false
    } else if (password.length < 8 || password.length > 100) {
      newErrors.password = "Password must be between 8 and 100 characters."
      isValid = false
    } else if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/.test(password)) {
      // Mirrors the backend UserCreateRequest pattern
      newErrors.password = "Password must contain an uppercase letter, a lowercase letter, a digit, and a special character (@$!%*?&). No other symbols."
      isValid = false
    }

    setErrors(newErrors)
    return isValid
  }

  const handleCreateUser = async (e: React.FormEvent) => {
    e.preventDefault()
    
    if (!validateForm()) return

    setIsSaving(true)
    try {
      await apiClient(`/users`, {
        method: "POST",
        body: JSON.stringify({ username, email, password, role }),
      })
      toast.success("User created successfully.")
      resetForm()
      onOpenChange(false) // Close the modal
      onSuccess()         // Refresh the user list
    } catch (error: any) {
      const fieldErrors = getFieldErrors(error)
      if (Object.keys(fieldErrors).length > 0) {
        setErrors({
          username: fieldErrors.username,
          email: fieldErrors.email,
          password: fieldErrors.password,
          general: fieldErrors.role,
        })
        return
      }
      const msg = (error.message || "").toLowerCase()
      // Map backend errors to specific fields if possible
      if (msg.includes("username")) {
        setErrors({ ...errors, username: error.message })
      } else if (msg.includes("email")) {
        setErrors({ ...errors, email: error.message })
      } else if (msg.includes("password")) {
        setErrors({ ...errors, password: error.message })
      } else {
        setErrors({ ...errors, general: error.message || "Failed to create user." })
      }
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent 
        className="sm:max-w-[425px] md:max-w-[600px]"
        // Prevent closing when clicking outside
        onInteractOutside={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <div className="flex items-center gap-2">
            <UserPlus className="h-5 w-5 text-primary" />
            <DialogTitle>Create New User</DialogTitle>
          </div>
          <DialogDescription>
            Fill out the form below to register a new user. The modal will not close if you click outside.
          </DialogDescription>
        </DialogHeader>

        {errors.general && (
          <div className="p-3 bg-destructive/10 text-destructive text-sm rounded-md border border-destructive/20">
            {errors.general}
          </div>
        )}

        <form noValidate onSubmit={handleCreateUser} className="grid gap-4 py-4 md:grid-cols-2">
          
          <div className="flex flex-col gap-2">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value)
                if (errors.username) setErrors({ ...errors, username: undefined })
              }}
              placeholder="johndoe"
              className={errors.username ? "border-destructive focus-visible:ring-destructive" : ""}
            />
            {errors.username && (
              <p className="text-xs font-medium text-destructive">{errors.username}</p>
            )}
            <p className="text-xs text-muted-foreground hidden md:block">3 to 50 characters.</p>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="email">Email Address</Label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value)
                if (errors.email) setErrors({ ...errors, email: undefined })
              }}
              placeholder="john@example.com"
              className={errors.email ? "border-destructive focus-visible:ring-destructive" : ""}
            />
            {errors.email && (
              <p className="text-xs font-medium text-destructive">{errors.email}</p>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="password">Initial Password</Label>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
                if (errors.password) setErrors({ ...errors, password: undefined })
              }}
              placeholder="Enter password"
              className={errors.password ? "border-destructive focus-visible:ring-destructive" : ""}
            />
            {errors.password && (
              <p className="text-xs font-medium text-destructive">{errors.password}</p>
            )}
            <p className="text-xs text-muted-foreground hidden md:block">Min 8 chars: upper, lower, digit and one of @$!%*?&</p>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="role">System Role</Label>
            <Select value={role} onValueChange={setRole}>
              <SelectTrigger id="role">
                <SelectValue placeholder="Select role" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ADMIN">Admin</SelectItem>
                <SelectItem value="USER">User</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <DialogFooter className="md:col-span-2 pt-4 flex gap-2 sm:justify-end">
            <Button 
              type="button" 
              variant="outline" 
              onClick={() => handleOpenChange(false)}
              disabled={isSaving}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={isSaving} className="gap-2">
              <UserPlus className="h-4 w-4" />
              {isSaving ? "Creating..." : "Create User"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
