export interface NetworkInterface {
  id: string;
  name: string;
  mac: string;
  ipv4: string[];
  ipv6: string[];
}
export interface Machine {
  deviceId: string;
  hostname: string;
  os: string;
  agentVersion: string;
  lastSeen: string;
  reportedAt: string;
  event: string;
  sequence: number;
  uptimeSeconds: number;
  online: boolean;
  peerIp: string;
  ipv4: string[];
  ipv6: string[];
  interfaces: NetworkInterface[];
}
export type ProviderType = "ALIYUN" | "TENCENT" | "CLOUDFLARE";
export interface Provider {
  id: number;
  name: string;
  type: ProviderType;
  accessKeyId: string;
  configured: boolean;
}
export interface Rule {
  id: number;
  name: string;
  machineDeviceId: string;
  interfaceId: string;
  interfaceName: string;
  recordType: "A" | "AAAA";
  providerId: number;
  domain: string;
  rr: string;
  ttl: number;
  line: string;
  recordId: string;
  addressMode: "AUTO" | "SPECIFIC";
  specificAddress: string;
  allowPrivate: boolean;
  enabled: boolean;
  lastStatus: string;
  lastMessage: string;
  lastAddress: string;
  lastSyncAt: string;
}
export interface SyncLog {
  id: number;
  ruleId: number;
  ruleName: string;
  status: string;
  action: string;
  address: string;
  message: string;
  createdAt: string;
}
export interface Dashboard {
  totalMachines: number;
  onlineMachines: number;
  offlineMachines: number;
  enabledRules: number;
  failedRules: number;
}
export interface Session {
  authenticated: boolean;
  username: string | null;
  csrfToken: string;
}
