"use client"

import * as React from "react"
import { useParams, useRouter } from "next/navigation"
import { DashboardHeader } from "@/components/dashboard-header"
import { apiClient, getFieldErrors } from "@/lib/api-client"
import { isValidEmail, isValidMsisdn } from "@/lib/validators"
import { DataTable } from "@/components/data-table"
import { type ColumnDef } from "@tanstack/react-table"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  UsersRound, Trash2, Edit2, UserPlus, X, Mail, Phone as PhoneIcon, ArrowLeft, RefreshCw
} from "lucide-react"
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from "@/components/ui/dialog"
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { toast } from "sonner"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/lib/auth-context"

interface Contact {
  id: number
  name: string
  email: string
  phoneNumber: string
}

interface ContactGroup {
  id: number
  name: string
  description: string
  contacts: Contact[]
}

export default function GroupDetailsPage() {
  const params = useParams()
  const id = params.id as string
  const router = useRouter()
  const { user } = useAuth()
  
  const [group, setGroup] = React.useState<ContactGroup | null>(null)
  const [isLoading, setIsLoading] = React.useState(true)
  const [contactModalOpen, setContactModalOpen] = React.useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false)
  const [selectedContact, setSelectedContact] = React.useState<Contact | null>(null)
  const [contactForm, setContactForm] = React.useState<Partial<Contact>>({})
  const [contactErrors, setContactErrors] = React.useState<{ name?: string; email?: string; phoneNumber?: string }>({})

  const fetchGroup = React.useCallback(async () => {
    if (!id) return
    setIsLoading(true)
    try {
      const data = await apiClient(`/groups/${id}`)
      setGroup(data)
    } catch (e: any) {
      toast.error("Failed to load group: " + e.message)
      router.push("/dashboard/contact-groups")
    } finally {
      setIsLoading(false)
    }
  }, [id, router])

  React.useEffect(() => {
    if (user && user.role !== "ADMIN") {
      toast.error("No permission")
      router.push("/dashboard")
      return
    }
    fetchGroup()
  }, [user, fetchGroup, router])

  const handleAddContact = async () => {
    const errors: typeof contactErrors = {}
    const email = contactForm.email?.trim() || ""
    const phone = contactForm.phoneNumber?.trim() || ""
    if (!contactForm.name?.trim()) errors.name = "Full name is required."
    else if (contactForm.name.length > 255) errors.name = "Name must not exceed 255 characters."
    if (email && !isValidEmail(email)) errors.email = "Please enter a valid email address."
    if (phone && !isValidMsisdn(phone)) errors.phoneNumber = "Phone number must be 7-15 digits (optional + prefix)."
    if (!email && !phone) {
      errors.email = "Provide an email or a phone number."
      errors.phoneNumber = "Provide an email or a phone number."
    }
    setContactErrors(errors)
    if (Object.keys(errors).length > 0) return

    try {
      await apiClient(`/groups/${id}/contacts`, { method: "POST", body: JSON.stringify(contactForm) })
      toast.success("Contact added successfully")
      setContactModalOpen(false)
      setContactForm({})
      fetchGroup()
    } catch (e: any) {
      const fieldErrors = getFieldErrors(e)
      if (Object.keys(fieldErrors).length > 0) {
        const { contactMethod, ...rest } = fieldErrors
        setContactErrors(contactMethod ? { email: contactMethod, phoneNumber: contactMethod, ...rest } : rest)
      }
      else toast.error("Failed to add contact: " + e.message)
    }
  }

  const handleDeleteContact = async () => {
    if (!selectedContact) return
    try {
      await apiClient(`/groups/contacts/${selectedContact.id}`, { method: "DELETE" })
      toast.success("Contact removed successfully")
      setDeleteDialogOpen(false)
      setSelectedContact(null)
      fetchGroup()
    } catch (e: any) {
      toast.error("Failed to remove contact: " + e.message)
    }
  }

  const confirmDelete = (contact: Contact) => {
    setSelectedContact(contact)
    setDeleteDialogOpen(true)
  }

  const columns: ColumnDef<Contact>[] = [
    {
      accessorKey: "name",
      header: "Full Name",
      cell: ({ row }) => <span className="font-semibold">{row.getValue("name")}</span>,
    },
    {
      accessorKey: "email",
      header: "Email",
      cell: ({ row }) => row.getValue("email") ? (
        <div className="flex items-center gap-1.5">
          <Mail className="h-3.5 w-3.5 text-muted-foreground" />
          <span>{row.getValue("email")}</span>
        </div>
      ) : "-",
    },
    {
      accessorKey: "phoneNumber",
      header: "Phone Number",
      cell: ({ row }) => row.getValue("phoneNumber") ? (
        <div className="flex items-center gap-1.5">
          <PhoneIcon className="h-3.5 w-3.5 text-muted-foreground" />
          <span className="font-mono">{row.getValue("phoneNumber")}</span>
        </div>
      ) : "-",
    },
    {
      id: "actions",
      cell: ({ row }) => (
        <div className="flex justify-end">
          <Button 
            variant="ghost" 
            size="icon" 
            className="h-8 w-8 text-destructive hover:bg-destructive/10" 
            onClick={() => confirmDelete(row.original)}
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      ),
    },
  ]

  if (isLoading) {
    return (
      <div className="p-8 space-y-6">
        <Skeleton className="h-10 w-1/4" />
        <Skeleton className="h-[400px] w-full" />
      </div>
    )
  }

  return (
    <>
      <DashboardHeader breadcrumbs={[
        { label: "Dashboard", href: "/dashboard" },
        { label: "Management", href: "#" },
        { label: "Contact Groups", href: "/dashboard/contact-groups" },
        { label: group?.name || "Details" },
      ]} />
      
      <div className="flex flex-1 flex-col gap-8 p-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <Button variant="ghost" size="icon" onClick={() => router.push("/dashboard/contact-groups")}>
              <ArrowLeft className="h-5 w-5" />
            </Button>
            <div className="grid gap-1">
              <h1 className="text-3xl font-bold tracking-tight">{group?.name}</h1>
              <p className="text-muted-foreground">{group?.description || "No description provided."}</p>
            </div>
          </div>
          <div className="flex gap-3">
             <Button variant="outline" onClick={fetchGroup}>
                <RefreshCw className="h-4 w-4 mr-2" /> Refresh
             </Button>
             <Button onClick={() => { setContactForm({}); setContactErrors({}); setContactModalOpen(true) }}>
                <UserPlus className="mr-2 h-4 w-4" /> Add Contact
             </Button>
          </div>
        </div>

        <Card className="border-none shadow-none bg-transparent">
          <CardHeader className="px-0 pt-0">
             <CardTitle>Group Members</CardTitle>
             <CardDescription>All contacts subscribed to this group will receive notifications.</CardDescription>
          </CardHeader>
          <CardContent className="px-0">
            <DataTable 
              columns={columns} 
              data={group?.contacts || []} 
              title={`${group?.name} Contacts`}
            />
          </CardContent>
        </Card>
      </div>

      {/* Contact Form Modal */}
      <Dialog open={contactModalOpen} onOpenChange={setContactModalOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Add Contact</DialogTitle>
            <DialogDescription>Add a new contact to {group?.name}.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="contact-name" className={contactErrors.name ? "text-destructive" : ""}>Full Name</Label>
              <Input
                id="contact-name"
                value={contactForm.name || ""}
                onChange={e => {
                  setContactForm({ ...contactForm, name: e.target.value })
                  if (contactErrors.name) setContactErrors({ ...contactErrors, name: undefined })
                }}
                placeholder="John Doe"
                aria-invalid={!!contactErrors.name}
                className={contactErrors.name ? "border-destructive focus-visible:ring-destructive" : ""}
              />
              {contactErrors.name && <p className="text-xs font-medium text-destructive">{contactErrors.name}</p>}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="contact-email" className={contactErrors.email ? "text-destructive" : ""}>Email Address</Label>
              <Input
                id="contact-email" type="email"
                value={contactForm.email || ""}
                onChange={e => {
                  setContactForm({ ...contactForm, email: e.target.value })
                  if (contactErrors.email) setContactErrors({ ...contactErrors, email: undefined })
                }}
                placeholder="john@example.com"
                aria-invalid={!!contactErrors.email}
                className={contactErrors.email ? "border-destructive focus-visible:ring-destructive" : ""}
              />
              {contactErrors.email && <p className="text-xs font-medium text-destructive">{contactErrors.email}</p>}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="contact-phoneNumber" className={contactErrors.phoneNumber ? "text-destructive" : ""}>Phone Number</Label>
              <Input
                id="contact-phoneNumber"
                value={contactForm.phoneNumber || ""}
                onChange={e => {
                  setContactForm({ ...contactForm, phoneNumber: e.target.value })
                  if (contactErrors.phoneNumber) setContactErrors({ ...contactErrors, phoneNumber: undefined })
                }}
                placeholder="249XXXXXXXXX"
                aria-invalid={!!contactErrors.phoneNumber}
                className={contactErrors.phoneNumber ? "border-destructive focus-visible:ring-destructive" : ""}
              />
              {contactErrors.phoneNumber && <p className="text-xs font-medium text-destructive">{contactErrors.phoneNumber}</p>}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setContactModalOpen(false)}>Cancel</Button>
            <Button onClick={handleAddContact}>Add Contact</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remove Contact?</AlertDialogTitle>
            <AlertDialogDescription>
              This will remove <strong>{selectedContact?.name}</strong> from the group <strong>{group?.name}</strong>. They will no longer receive notifications for this group.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteContact} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              Remove Contact
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
