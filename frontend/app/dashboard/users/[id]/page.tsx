"use client"

import React, { useState, useEffect } from "react"
import { useParams, useRouter } from "next/navigation"
import { DashboardHeader } from "@/components/dashboard-header"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { toast } from "sonner"
import {
  User,
  Mail,
  IdCard,
  ShieldCheck,
  Save,
  Trash2,
  Power,
  ArrowLeft
} from "lucide-react"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/lib/auth-context"

export default function UserDetailsPage() {
  const { id } = useParams()
  const router = useRouter()
  const { user: currentUser } = useAuth()

  const [isLoading, setIsLoading] = useState(true)
  const [user, setUser] = useState<any>(null)

  useEffect(() => {
    if (currentUser && currentUser.role !== "ADMIN") {
      toast.error("You do not have permission to access this page.", { id: "role-error" })
      router.push("/dashboard")
    }
  }, [currentUser, router])

  // Form states
  const [email, setEmail] = useState("")
  const [emailError, setEmailError] = useState("")
  const [role, setRole] = useState("USER")

  // Action states
  const [isSavingInfo, setIsSavingInfo] = useState(false)
  const [isTogglingStatus, setIsTogglingStatus] = useState(false)
  const [isDeleting, setIsDeleting] = useState(false)
  const [confirmAction, setConfirmAction] = useState<"toggle" | "delete" | null>(null)

  useEffect(() => {
    const fetchUser = async () => {
      try {
        const data = await apiClient(`/users/${id}`)
        setUser(data)
        setEmail(data.email)
        setRole(data.role)
      } catch (error: any) {
        toast.error(error.message || "Failed to load user details.")
        router.push("/dashboard/users")
      } finally {
        setIsLoading(false)
      }
    }
    if (id) {
      fetchUser()
    }
  }, [id, router])

  const handleUpdateInfo = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!user?.id) return

    setEmailError("")
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!email.trim()) {
      setEmailError("Email address is required.")
      return
    }
    if (!emailRegex.test(email)) {
      setEmailError("Please enter a valid email address.")
      return
    }

    setIsSavingInfo(true)
    try {
      const updated = await apiClient(`/users/${user.id}`, {
        method: "PUT",
        body: JSON.stringify({ email, role, active: user.active }), // active is updated via toggle endpoint
      })
      setUser(updated)
      toast.success("User information updated.")
    } catch (error: any) {
      const fieldErrors = getFieldErrors(error)
      if (fieldErrors.email) {
        setEmailError(fieldErrors.email)
      } else if (error.message && error.message.toLowerCase().includes("email")) {
        setEmailError(error.message)
      } else {
        toast.error(error.message || "Failed to update user information.")
      }
    } finally {
      setIsSavingInfo(false)
    }
  }

  const handleToggleStatus = async () => {
    setIsTogglingStatus(true)
    try {
      const updated = await apiClient(`/users/${user.id}/toggle-status`, { method: "PATCH" })
      setUser(updated)
      toast.success(`User set to ${updated.active ? 'active' : 'inactive'}.`)
      setConfirmAction(null)
    } catch (error: any) {
      toast.error(error.message || "Failed to toggle status.")
    } finally {
      setIsTogglingStatus(false)
    }
  }

  const handleDeleteUser = async () => {
    setIsDeleting(true)
    try {
      await apiClient(`/users/${user.id}`, { method: "DELETE" })
      toast.success("User deleted successfully.")
      router.push("/dashboard/users")
    } catch (error: any) {
      toast.error(error.message || "Failed to delete user.")
      setIsDeleting(false)
      setConfirmAction(null)
    }
  }

  if (isLoading || !user) {
    return (
      <div className="flex flex-1 flex-col gap-6 p-6">
        <div className="flex items-center gap-2 mb-4">
          <Skeleton className="h-8 w-32" />
        </div>
        <div className="flex flex-col md:flex-row items-start gap-6">
          <Skeleton className="h-96 w-full md:w-72 shrink-0 rounded-xl" />
          <Skeleton className="h-96 flex-1 w-full rounded-xl" />
        </div>
      </div>
    )
  }

  const initials = user.username?.substring(0, 2).toUpperCase() || "GU"

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "Dashboard", href: "/dashboard" },
          { label: "Management" },
          { label: "Users", href: "/dashboard/users" },
          { label: "User Details" },
        ]}
      />
      
      <div className="flex flex-1 flex-col gap-6 p-6">
        <div className="flex items-center justify-between mb-2">
          <Button variant="ghost" className="gap-2" onClick={() => router.push("/dashboard/users")}>
            <ArrowLeft />
            Back to Users
          </Button>

          <div className="flex items-center gap-2">
            <Button 
              variant={user.active ? "secondary" : "default"} 
              size="sm" 
              className="gap-2"
              onClick={() => setConfirmAction("toggle")}
              disabled={isTogglingStatus}
            >
              <Power />
              {isTogglingStatus ? "Updating..." : user.active ? "Deactivate User" : "Activate User"}
            </Button>
            <Button 
              variant="destructive" 
              size="sm" 
              className="gap-2"
              onClick={() => setConfirmAction("delete")}
              disabled={isDeleting}
            >
              <Trash2 />
              {isDeleting ? "Deleting..." : "Delete User"}
            </Button>
          </div>
        </div>

        <div className="flex flex-col md:flex-row items-start gap-6">
          {/* Left — Avatar card */}
          <Card className="w-full md:w-72 overflow-hidden shrink-0">
            <div className="h-28 bg-gradient-to-r from-primary/20 via-primary/10 to-background" />
            <CardContent className="relative -mt-10 flex flex-col items-center pb-8">
              <Avatar className="h-20 w-20 border-4 border-background shadow-lg">
                <AvatarFallback className="text-2xl bg-primary text-primary-foreground font-bold">
                  {initials}
                </AvatarFallback>
              </Avatar>
              <h2 className="mt-4 text-lg font-bold">{user.username}</h2>
              <p className="text-sm text-muted-foreground">{user.email}</p>
              <Badge variant={user.role === 'ADMIN' ? 'default' : 'secondary'} className="mt-3 capitalize">
                {user.role?.toLowerCase()}
              </Badge>

              <Separator className="my-5 w-full" />

              <div className="w-full space-y-4 text-left">
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-muted">
                    <IdCard className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-xs text-muted-foreground">User ID</span>
                    <span className="text-sm font-medium">#{user.id}</span>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-muted">
                    <ShieldCheck className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-xs text-muted-foreground">Role</span>
                    <span className="text-sm font-medium text-primary">{user.role}</span>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-muted">
                    <User className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-xs text-muted-foreground">Status</span>
                    <span className={`text-sm font-medium ${user.active ? "text-green-500" : "text-muted-foreground"}`}>
                      {user.active ? "Active" : "Inactive"}
                    </span>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Right — Edit form */}
          <div className="flex-1 space-y-6 w-full min-w-0">
            <Card>
              <CardHeader>
                <div className="flex items-center gap-2">
                  <Mail className="h-5 w-5 text-primary" />
                  <div>
                    <CardTitle>Edit User Details</CardTitle>
                    <CardDescription>Update the email address and system role of this user.</CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                <form noValidate onSubmit={handleUpdateInfo} className="grid gap-5 md:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="username">Username</Label>
                    <Input
                      id="username"
                      value={user.username}
                      disabled
                      className="bg-muted/40 cursor-not-allowed"
                    />
                    <p className="text-xs text-muted-foreground">Username cannot be changed.</p>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="email">Email Address</Label>
                    <Input
                      id="email"
                      type="email"
                      value={email}
                      onChange={(e) => {
                        setEmail(e.target.value)
                        if (emailError) setEmailError("")
                      }}
                      placeholder="Enter email address"
                      required
                      className={emailError ? "border-destructive focus-visible:ring-destructive" : ""}
                    />
                    {emailError && (
                      <p className="text-sm font-medium text-destructive mt-1">{emailError}</p>
                    )}
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="role">Role</Label>
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

                  <div className="md:col-span-2 flex justify-end pt-2">
                    <Button type="submit" disabled={isSavingInfo} className="gap-2">
                      <Save className="h-4 w-4" />
                      {isSavingInfo ? "Saving..." : "Save Changes"}
                    </Button>
                  </div>
                </form>
              </CardContent>
            </Card>
          </div>
        </div>
      </div>

      <AlertDialog open={confirmAction !== null} onOpenChange={(isOpen) => !isOpen && setConfirmAction(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {confirmAction === "delete" ? "Delete User" : `${user.active ? "Deactivate" : "Activate"} User`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {confirmAction === "delete" 
                ? "This action cannot be undone. This will permanently delete the user account and remove their data from our servers."
                : `Are you sure you want to ${user.active ? "deactivate" : "activate"} this user account? ${user.active ? "They will no longer be able to log in." : "They will regain access to the system."}`
              }
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isDeleting || isTogglingStatus}>Cancel</AlertDialogCancel>
            <Button
              variant={confirmAction === "delete" ? "destructive" : "default"}
              onClick={() => confirmAction === "delete" ? handleDeleteUser() : handleToggleStatus()}
              disabled={isDeleting || isTogglingStatus}
            >
              {isDeleting || isTogglingStatus ? "Processing..." : "Continue"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
