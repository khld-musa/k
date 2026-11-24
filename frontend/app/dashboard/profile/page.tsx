"use client";

import React, { useState } from "react";
import { DashboardHeader } from "@/components/dashboard-header";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useAuth } from "@/lib/auth-context";
import { apiClient, getFieldErrors } from "@/lib/api-client";
import { isValidEmail } from "@/lib/validators";
import { toast } from "sonner";
import {
  User,
  Mail,
  IdCard,
  ShieldCheck,
  Lock,
  Save,
} from "lucide-react";

export default function ProfilePage() {
  const { user, login, token, clearMustChangePassword } = useAuth();
  const initials = user?.username?.substring(0, 2).toUpperCase() || "GU";

  const isAdmin = user?.role === "ADMIN";
  const [email, setEmail] = useState(user?.email || "");
  const [emailError, setEmailError] = useState("");
  const [role, setRole] = useState(user?.role ?? "USER");
  const [active, setActive] = useState<boolean>(user?.active ?? true);
  const [isSavingInfo, setIsSavingInfo] = useState(false);

  // Password form state
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordErrors, setPasswordErrors] = useState<{ newPassword?: string; confirmPassword?: string }>({});
  const [isSavingPassword, setIsSavingPassword] = useState(false);

  const handleUpdateInfo = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!user?.id) return;
    
    setEmailError("");
    if (!email.trim()) {
      setEmailError("Email address is required.");
      return;
    }
    if (!isValidEmail(email)) {
      setEmailError("Please enter a valid email address.");
      return;
    }

    setIsSavingInfo(true);
    try {
      const updated = await apiClient(`/users/${user.id}`, {
        method: "PUT",
        body: JSON.stringify({ email, role, active }),
      });
      const storedRefresh = localStorage.getItem("refresh_token") || "";
      login(token!, storedRefresh, updated);
      toast.success("Account information updated.");
    } catch (error: any) {
      const fieldErrors = getFieldErrors(error);
      if (fieldErrors.email) {
        setEmailError(fieldErrors.email);
      } else if (error.message && error.message.toLowerCase().includes("email")) {
        setEmailError(error.message);
      } else {
        toast.error(error.message || "Failed to update account information.");
      }
    } finally {
      setIsSavingInfo(false);
    }
  };

  const handleUpdatePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!user?.id) return;

    // Mirrors backend UserUpdateRequest: 6-100 characters
    const errors: typeof passwordErrors = {};
    if (!newPassword) {
      errors.newPassword = "New password is required.";
    } else if (newPassword.length < 6 || newPassword.length > 100) {
      errors.newPassword = "Password must be between 6 and 100 characters.";
    }
    if (!confirmPassword) {
      errors.confirmPassword = "Please confirm the new password.";
    } else if (newPassword !== confirmPassword) {
      errors.confirmPassword = "Passwords do not match.";
    }
    setPasswordErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setIsSavingPassword(true);
    try {
      await apiClient(`/users/${user.id}`, {
        method: "PUT",
        body: JSON.stringify({ password: newPassword }),
      });
      setNewPassword("");
      setConfirmPassword("");
      setPasswordErrors({});
      clearMustChangePassword();
      toast.success("Password updated successfully.");
    } catch (error: any) {
      const fieldErrors = getFieldErrors(error);
      if (fieldErrors.password) {
        setPasswordErrors({ newPassword: fieldErrors.password });
      } else {
        toast.error(error.message || "Failed to update password.");
      }
    } finally {
      setIsSavingPassword(false);
    }
  };

  return (
    <>
      <DashboardHeader
        breadcrumbs={[
          { label: "Dashboard", href: "/dashboard" },
          { label: "Profile" },
        ]}
      />
      <div className="flex flex-1 flex-col gap-6 p-6">
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
              <h2 className="mt-4 text-lg font-bold">{user?.username}</h2>
              <p className="text-sm text-muted-foreground">{email}</p>
              <Badge variant="outline" className="mt-3 capitalize">
                {role?.toLowerCase()}
              </Badge>

              <Separator className="my-5 w-full" />

              <div className="w-full space-y-4 text-left">
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-muted">
                    <IdCard className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-xs text-muted-foreground">User ID</span>
                    <span className="text-sm font-medium">#{user?.id}</span>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-muted">
                    <ShieldCheck className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-xs text-muted-foreground">Role</span>
                    <span className="text-sm font-medium text-primary">{role}</span>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-muted">
                    <User className="h-4 w-4 text-primary" />
                  </div>
                  <div className="flex flex-col">
                    <span className="text-xs text-muted-foreground">Status</span>
                    <span className={`text-sm font-medium ${active ? "text-green-500" : "text-muted-foreground"}`}>
                      {active ? "Active" : "Inactive"}
                    </span>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Right — Edit forms */}
          <div className="flex-1 space-y-6 w-full min-w-0">

            {/* Account Information */}
            <Card>
              <CardHeader>
                <div className="flex items-center gap-2">
                  <Mail className="h-5 w-5 text-primary" />
                  <div>
                    <CardTitle>Account Information</CardTitle>
                    <CardDescription>Update your email address, role, and account status.</CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                <form noValidate onSubmit={handleUpdateInfo} className="grid gap-5 md:grid-cols-2">
                  {!isAdmin && (
                    <div className="md:col-span-2 p-3 bg-muted/50 rounded-md border border-muted text-sm text-muted-foreground flex items-center gap-2">
                      <ShieldCheck className="h-4 w-4" />
                      Only administrators can modify account information. You can still change your password below.
                    </div>
                  )}

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="username">Username</Label>
                    <Input
                      id="username"
                      value={user?.username || ""}
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
                        setEmail(e.target.value);
                        if (emailError) setEmailError("");
                      }}
                      placeholder="Enter email address"
                      aria-invalid={!!emailError}
                      disabled={!isAdmin}
                      className={emailError ? "border-destructive focus-visible:ring-destructive" : ""}
                    />
                    {emailError && (
                      <p className="text-sm font-medium text-destructive mt-1">{emailError}</p>
                    )}
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="role">Role</Label>
                    <Select value={role} onValueChange={setRole} disabled={!isAdmin}>
                      <SelectTrigger id="role">
                        <SelectValue placeholder="Select role" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ADMIN">Admin</SelectItem>
                        <SelectItem value="USER">User</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="status">Account Status</Label>
                    <Select
                      value={active ? "active" : "inactive"}
                      onValueChange={(v) => setActive(v === "active")}
                      disabled={!isAdmin}
                    >
                      <SelectTrigger id="status">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="active">Active</SelectItem>
                        <SelectItem value="inactive">Inactive</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {isAdmin && (
                    <div className="md:col-span-2 flex justify-end pt-2">
                      <Button type="submit" disabled={isSavingInfo} className="gap-2">
                        <Save className="h-4 w-4" />
                        {isSavingInfo ? "Saving..." : "Save Changes"}
                      </Button>
                    </div>
                  )}
                </form>
              </CardContent>
            </Card>

            {/* Change Password */}
            <Card>
              <CardHeader>
                <div className="flex items-center gap-2">
                  <Lock className="h-5 w-5 text-primary" />
                  <div>
                    <CardTitle>Change Password</CardTitle>
                    <CardDescription>Set a new password. Minimum 6 characters.</CardDescription>
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                <form noValidate onSubmit={handleUpdatePassword} className="grid gap-5 md:grid-cols-2">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="new-password">New Password</Label>
                    <Input
                      id="new-password"
                      type="password"
                      value={newPassword}
                      onChange={(e) => {
                        setNewPassword(e.target.value);
                        if (passwordErrors.newPassword) setPasswordErrors({ ...passwordErrors, newPassword: undefined });
                      }}
                      placeholder="Enter new password"
                      aria-invalid={!!passwordErrors.newPassword}
                      className={passwordErrors.newPassword ? "border-destructive focus-visible:ring-destructive" : ""}
                    />
                    {passwordErrors.newPassword && (
                      <p className="text-sm font-medium text-destructive mt-1">{passwordErrors.newPassword}</p>
                    )}
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="confirm-password">Confirm Password</Label>
                    <Input
                      id="confirm-password"
                      type="password"
                      value={confirmPassword}
                      onChange={(e) => {
                        setConfirmPassword(e.target.value);
                        if (passwordErrors.confirmPassword) setPasswordErrors({ ...passwordErrors, confirmPassword: undefined });
                      }}
                      placeholder="Re-enter new password"
                      aria-invalid={!!passwordErrors.confirmPassword}
                      className={passwordErrors.confirmPassword ? "border-destructive focus-visible:ring-destructive" : ""}
                    />
                    {passwordErrors.confirmPassword && (
                      <p className="text-sm font-medium text-destructive mt-1">{passwordErrors.confirmPassword}</p>
                    )}
                  </div>

                  <div className="md:col-span-2 flex justify-end pt-2">
                    <Button
                      type="submit"
                      disabled={isSavingPassword}
                      className="gap-2"
                    >
                      <Lock className="h-4 w-4" />
                      {isSavingPassword ? "Updating..." : "Update Password"}
                    </Button>
                  </div>
                </form>
              </CardContent>
            </Card>

          </div>
        </div>
      </div>
    </>
  );
}
