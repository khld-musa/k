import { Skeleton } from "@/components/ui/skeleton"

export default function DashboardLoading() {
  return (
    <div className="flex flex-1 flex-col gap-8 p-6 lg:p-10">
      {/* Header skeleton */}
      <div className="flex h-16 shrink-0 items-center gap-2 border-b px-4 -mx-6 -mt-6 lg:-mx-10">
        <Skeleton className="h-6 w-32" />
      </div>
      {/* Page title skeleton */}
      <div className="flex flex-col gap-2">
        <Skeleton className="h-10 w-48" />
        <Skeleton className="h-5 w-80" />
      </div>
      {/* Stats cards skeleton */}
      <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-36 rounded-xl" />
        ))}
      </div>
      {/* Chart area skeleton */}
      <div className="grid gap-6 lg:grid-cols-7">
        <Skeleton className="h-96 lg:col-span-4 rounded-xl" />
        <Skeleton className="h-96 lg:col-span-3 rounded-xl" />
      </div>
    </div>
  )
}
