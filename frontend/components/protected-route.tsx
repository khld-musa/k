"use client";

import { useAuth } from "@/lib/auth-context";
import { useRouter, usePathname } from "next/navigation";
import { useEffect } from "react";

export function ProtectedRoute({ children, allowedRoles }: { children: React.ReactNode, allowedRoles?: string[] }) {
  const { user, token, isLoading, mustChangePassword } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!isLoading && !token) {
      router.push("/login");
    }
  }, [token, isLoading, router]);

  useEffect(() => {
    if (!isLoading && token && user && allowedRoles && !allowedRoles.includes(user.role)) {
      router.push("/dashboard");
    }
  }, [token, user, isLoading, allowedRoles, router]);

  // Force redirect to profile page if password change is required
  useEffect(() => {
    if (!isLoading && token && mustChangePassword && pathname !== "/dashboard/profile") {
      router.push("/dashboard/profile");
    }
  }, [token, isLoading, mustChangePassword, pathname, router]);

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
      </div>
    );
  }

  if (!token) {
    return null;
  }

  return <>{children}</>;
}
