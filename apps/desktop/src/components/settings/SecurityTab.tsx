import React from "react";
import { ShieldCheck } from "lucide-react";
import { useSettings } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const SecurityTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();

  return (
    <div className="flex flex-col gap-6 animate-[toast-slide-in_0.2s_ease-out]">
      <div>
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4 select-none">
          Security & Trust
        </h3>

        {/* Trusted Devices list */}
        <div className="flex flex-col gap-2 pb-5 border-b border-white/5">
          <div className="flex flex-col">
            <span className="text-sm font-semibold text-slate-200">Trusted devices</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Devices that can send files without manual approval.
            </span>
          </div>
          <div className="flex flex-col items-center justify-center p-8 bg-[#0a0f18]/40 border border-dashed border-white/5 rounded-2xl select-none mt-2">
            <ShieldCheck className="text-slate-600 mb-2 w-7 h-7" strokeWidth={1.5} />
            <span className="text-xs font-medium text-slate-400">No trusted devices yet</span>
            <span className="text-[10px] text-slate-650 mt-0.5 leading-normal text-center max-w-[200px]">
              You can trust devices directly from incoming transfer prompts.
            </span>
          </div>
        </div>

        {/* Require Confirmation Switch */}
        <div className="flex items-center justify-between py-4.5 border-b border-white/5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Require confirmation</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Always ask for approval before accepting incoming transfers.
            </span>
          </div>
          <Switch checked={settings.requireConfirmation} onChange={(val) => updateSetting("requireConfirmation", val)} label="Require confirmation" />
        </div>

        {/* Auto accept switch (disabled placeholder) */}
        <div className="flex items-center justify-between py-4.5 opacity-55 select-none">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Auto accept trusted devices</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Skip verification prompts for devices you have previously trusted.
            </span>
          </div>
          <Switch checked={false} onChange={() => {}} disabled label="Auto accept trusted devices (Disabled)" />
        </div>
      </div>
    </div>
  );
};
