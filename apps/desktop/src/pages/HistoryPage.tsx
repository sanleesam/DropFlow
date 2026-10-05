import React, { useState, useMemo } from "react";
import { invoke } from "@tauri-apps/api/core";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import {
  Search,
  ChevronDown,
  ChevronUp,
  FileText,
  FolderOpen,
  Copy,
  ArrowDownLeft,
  ArrowUpRight,
  XCircle,
} from "lucide-react";
import { RecentTransfer, formatRelativeTimestamp } from "../utils/transferSessionManager";
import { formatBytes } from "../utils/formatters";
import { useToast } from "../components/ToastProvider";

interface HistoryPageProps {
  recentTransfers: RecentTransfer[];
}

type FilterTab = "all" | "send" | "receive" | "failed";

export const HistoryPage: React.FC<HistoryPageProps> = ({ recentTransfers }) => {
  const { addToast } = useToast();
  const [searchQuery, setSearchQuery] = useState("");
  const [activeTab, setActiveTab] = useState<FilterTab>("all");
  const [expandedSessionIds, setExpandedSessionIds] = useState<Set<string>>(new Set());

  const toggleExpand = (id: string) => {
    setExpandedSessionIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  // Sub-millisecond memoized filter and search
  const filteredTransfers = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();

    return recentTransfers.filter((tx) => {
      // 1. Tab filter
      if (activeTab === "send" && tx.direction !== "send") return false;
      if (activeTab === "receive" && tx.direction !== "receive") return false;
      if (activeTab === "failed" && tx.status !== "failed") return false;

      // 2. Search query filter
      if (!query) return true;

      const matchName = tx.fileName.toLowerCase().includes(query);
      const matchDevice = tx.deviceName.toLowerCase().includes(query);
      const matchFiles = tx.files?.some((f) => f.relativePath.toLowerCase().includes(query)) ?? false;

      return matchName || matchDevice || matchFiles;
    });
  }, [recentTransfers, searchQuery, activeTab]);

  const handleOpenFile = async (path: string) => {
    try {
      await openPath(path);
    } catch (openerErr) {
      try {
        await invoke("open_received_file", { path });
      } catch (err) {
        console.error("Failed to open file:", err);
        addToast("Failed to open file.", "error");
      }
    }
  };

  const handleOpenFolder = async (path: string) => {
    try {
      await revealItemInDir(path);
    } catch (openerErr) {
      try {
        await openPath(path);
      } catch (err) {
        console.error("Failed to open folder:", err);
        addToast("Failed to open folder.", "error");
      }
    }
  };

  const handleCopyPath = (path: string) => {
    navigator.clipboard.writeText(path).then(
      () => addToast("File path copied to clipboard", "success"),
      () => addToast("Failed to copy path", "error")
    );
  };

  return (
    <div className="flex flex-col gap-5 w-full max-w-[980px] mx-auto p-4 sm:p-6 transition-opacity duration-150 ease-[cubic-bezier(0.22,1,0.36,1)]">
      {/* ── Page Header ── */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-2 border-b border-white/[0.07]">
        <div>
          <h1 className="text-xl font-bold text-neutral-100 tracking-tight flex items-center gap-2 select-none">
            <span>Transfer History</span>
            <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-neutral-800 border border-white/[0.08] text-neutral-400">
              {recentTransfers.length} {recentTransfers.length === 1 ? "session" : "sessions"}
            </span>
          </h1>
          <p className="text-xs text-neutral-400 mt-1">
            Complete record of your sent and received files across local devices.
          </p>
        </div>
      </div>

      {/* ── Search & Filter Controls ── */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3">
        {/* Search Bar */}
        <div className="relative flex-1">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-500 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search by filename or device name..."
            className="w-full pl-9 pr-8 py-2 rounded-xl bg-neutral-900/80 border border-white/[0.08] text-xs text-neutral-200 placeholder-neutral-500 outline-none focus:border-blue-500/50 focus:ring-1 focus:ring-blue-500/30 transition-all"
          />
          {searchQuery && (
            <button
              type="button"
              onClick={() => setSearchQuery("")}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 text-neutral-500 hover:text-neutral-300 text-xs"
            >
              ×
            </button>
          )}
        </div>

        {/* Filter Tabs */}
        <div className="flex items-center gap-1 bg-neutral-950/60 p-1 rounded-xl border border-white/[0.07] select-none shrink-0">
          {(["all", "send", "receive", "failed"] as FilterTab[]).map((tab) => (
            <button
              key={tab}
              type="button"
              onClick={() => setActiveTab(tab)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium capitalize transition-all duration-150 ${activeTab === tab
                  ? "bg-neutral-800 text-white shadow-sm border border-white/[0.08]"
                  : "text-neutral-400 hover:text-neutral-200 hover:bg-white/[0.02]"
                }`}
            >
              {tab === "all" ? "All" : tab === "send" ? "Sent" : tab === "receive" ? "Received" : "Failed"}
            </button>
          ))}
        </div>
      </div>

      {/* ── History Sessions List ── */}
      {filteredTransfers.length > 0 ? (
        <div className="flex flex-col gap-2.5">
          {filteredTransfers.map((tx) => {
            const isExpanded = expandedSessionIds.has(tx.id);
            const isFailed = tx.status === "failed";
            const isReceive = tx.direction === "receive";
            const displayTime = tx.timestampMs ? formatRelativeTimestamp(tx.timestampMs) : tx.timestamp;

            return (
              <div
                key={tx.id}
                className="flex flex-col rounded-xl border border-white/[0.08] bg-neutral-900/60 overflow-hidden transition-all duration-150"
              >
                {/* Session Header Item */}
                <div
                  onClick={() => toggleExpand(tx.id)}
                  className="flex items-center justify-between px-4 py-3 cursor-pointer hover:bg-white/[0.02] transition-colors select-none"
                >
                  <div className="flex items-center gap-3 min-w-0 flex-1 pr-3">
                    {/* Direction / Status Icon */}
                    <span
                      className={`flex-shrink-0 flex items-center justify-center w-8 h-8 rounded-lg border ${isFailed
                          ? "bg-red-500/10 border-red-500/20 text-red-400"
                          : isReceive
                            ? "bg-blue-500/10 border-blue-500/20 text-blue-400"
                            : "bg-emerald-500/10 border-emerald-500/20 text-emerald-400"
                        }`}
                    >
                      {isFailed ? (
                        <XCircle size={16} />
                      ) : isReceive ? (
                        <ArrowDownLeft size={16} />
                      ) : (
                        <ArrowUpRight size={16} />
                      )}
                    </span>

                    {/* Session Labels */}
                    <div className="flex flex-col min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-neutral-100 truncate">
                          {tx.totalFiles > 1 ? `${tx.totalFiles} files` : tx.fileName}
                        </span>
                        {tx.totalFiles > 1 && (
                          <span className="px-1.5 py-0.2 rounded text-[10px] bg-neutral-800 border border-white/[0.06] text-neutral-400">
                            Batch
                          </span>
                        )}
                      </div>
                      <span className="text-[11px] text-neutral-400 mt-0.5 flex items-center gap-1.5">
                        <span>{isReceive ? "↓ Received from" : "↑ Sent to"}</span>
                        <span className="text-neutral-200 font-medium">{tx.deviceName}</span>
                        <span>•</span>
                        <span className="text-neutral-400">{displayTime}</span>
                      </span>
                    </div>
                  </div>

                  {/* Metadata Right Badges */}
                  <div className="flex items-center gap-3 flex-shrink-0 text-right select-none">
                    <div className="flex flex-col items-end">
                      <span className="text-xs text-neutral-300 font-mono font-medium">{tx.size}</span>
                      <span
                        className={`text-[10px] font-medium mt-0.5 ${isFailed ? "text-red-400" : "text-emerald-400"
                          }`}
                      >
                        {isFailed ? "Failed" : "Completed"}
                      </span>
                    </div>

                    <button
                      type="button"
                      aria-label="Toggle expansion"
                      className="p-1 rounded-md text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800 transition-colors"
                    >
                      {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                    </button>
                  </div>
                </div>

                {/* Expanded Session File Details */}
                {isExpanded && (
                  <div className="px-4 py-3 border-t border-white/[0.06] bg-neutral-950/40 flex flex-col gap-3">
                    {/* Error message if failed */}
                    {isFailed && tx.error && (
                      <div className="px-3 py-2 rounded-lg bg-red-500/10 border border-red-500/20 text-xs text-red-400">
                        <span className="font-semibold">Error:</span> {tx.error}
                      </div>
                    )}

                    {/* Files list */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-[10px] font-semibold uppercase tracking-wider text-neutral-500">
                        Files in this transfer ({tx.files?.length || tx.totalFiles})
                      </span>

                      {tx.files && tx.files.length > 0 ? (
                        <div className="flex flex-col divide-y divide-white/[0.04] rounded-lg border border-white/[0.06] bg-neutral-900/80 overflow-hidden">
                          {tx.files.map((file, idx) => (
                            <div
                              key={idx}
                              className="flex items-center justify-between px-3 py-2 text-xs hover:bg-white/[0.02] transition-colors"
                            >
                              <div className="flex items-center gap-2.5 min-w-0 flex-1 pr-2">
                                <FileText size={14} className="text-neutral-400 flex-shrink-0" />
                                <div className="flex flex-col min-w-0">
                                  <span className="text-neutral-200 font-medium truncate">
                                    {file.relativePath}
                                  </span>
                                  {file.finalPath && (
                                    <span className="text-[10px] font-mono text-neutral-500 truncate max-w-[380px]" title={file.finalPath}>
                                      {file.finalPath}
                                    </span>
                                  )}
                                </div>
                              </div>

                              <div className="flex items-center gap-2 flex-shrink-0">
                                <span className="text-xs text-neutral-400 font-mono mr-1">
                                  {formatBytes(file.sizeBytes)}
                                </span>

                                {file.finalPath && (
                                  <>
                                    <button
                                      type="button"
                                      onClick={() => handleOpenFile(file.finalPath!)}
                                      title="Open file"
                                      className="p-1 rounded bg-neutral-800 text-neutral-300 hover:text-white hover:bg-neutral-700 transition-colors"
                                    >
                                      <FileText size={12} />
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() => handleOpenFolder(file.finalPath!)}
                                      title="Reveal in folder"
                                      className="p-1 rounded bg-neutral-800 text-neutral-300 hover:text-white hover:bg-neutral-700 transition-colors"
                                    >
                                      <FolderOpen size={12} />
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() => handleCopyPath(file.finalPath!)}
                                      title="Copy path"
                                      className="p-1 rounded bg-neutral-800 text-neutral-300 hover:text-white hover:bg-neutral-700 transition-colors"
                                    >
                                      <Copy size={12} />
                                    </button>
                                  </>
                                )}
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-neutral-500 py-1">
                          Single file transfer ({tx.fileName})
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      ) : (
        /* Empty State */
        <div className="py-12 flex flex-col items-center justify-center text-center rounded-xl border border-white/[0.07] bg-neutral-900/30">
          <Search size={22} className="text-neutral-600 mb-2" />
          <h3 className="text-xs font-semibold text-neutral-300">No transfers found</h3>
          <p className="text-xs text-neutral-500 mt-1 max-w-xs select-none">
            {searchQuery
              ? `No transfers matching "${searchQuery}"`
              : activeTab !== "all"
                ? `No transfers in category "${activeTab}"`
                : "Your transfer history will appear here once you send or receive files."}
          </p>
        </div>
      )}
    </div>
  );
};
