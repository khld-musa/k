"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { apiClient } from "@/lib/api-client";

interface User {
  id: number;
  username: string;
  email: string;
  role: string;
  active: boolean;
  mustChangePassword?: boolean;
}

interface AuthContextType {
  user: User | null;
  token: string | null;
  mustChangePassword: boolean;
  login: (token: string, refreshToken: string, user: User, mustChangePassword?: boolean) => void;
  logout: () => Promise<void>;
  clearMustChangePassword: () => void;
  isLoading: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [mustChangePassword, setMustChangePassword] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    const storedToken = localStorage.getItem("auth_token");
    const storedUser = localStorage.getItem("auth_user");

    const verifySession = async () => {
      try {
        await apiClient("/auth/me");
        setIsLoading(false);
      } catch (err) {
        setIsLoading(false);
      }
    };

    if (storedToken && storedUser) {
      try {
        setToken(storedToken);
        setUser(JSON.parse(storedUser));
        verifySession();
      } catch {
        // Corrupted user data — clear storage and force re-login
        localStorage.removeItem("auth_token");
        localStorage.removeItem("refresh_token");
        localStorage.removeItem("auth_user");
        setIsLoading(false);
      }
    } else {
      setIsLoading(false);
    }
  }, []);

  const login = (newToken: string, newRefreshToken: string, newUser: User, forcePwdChange?: boolean) => {
    setToken(newToken);
    setUser(newUser);
    setMustChangePassword(forcePwdChange || false);
    localStorage.setItem("auth_token", newToken);
    localStorage.setItem("refresh_token", newRefreshToken);
    localStorage.setItem("auth_user", JSON.stringify(newUser));
  };

  const logout = async () => {
    try {
      if (token) {
        await apiClient("/auth/logout", { method: "POST" });
      }
    } catch (err) {
      console.warn("Backend logout failed or token already invalid", err);
    } finally {
      setToken(null);
      setUser(null);
      setMustChangePassword(false);
      localStorage.removeItem("auth_token");
      localStorage.removeItem("refresh_token");
      localStorage.removeItem("auth_user");
      router.push("/login");
    }
  };

  const clearMustChangePassword = () => {
    setMustChangePassword(false);
  };

  return (
    <AuthContext.Provider value={{ user, token, mustChangePassword, login, logout, clearMustChangePassword, isLoading }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
