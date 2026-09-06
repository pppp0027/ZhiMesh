<script setup lang="ts">
import { computed } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import { NButton, NInput, NInputNumber, NRadio, NRadioGroup } from 'naive-ui'
import NodePropertyInput from '../NodePropertyInput.vue'
import ReferComment from '../ReferComment.vue'
import ReferTooltip from '../ReferTooltip.vue'
import WfVariableSelector from '../WfVariableSelector.vue'
import { t } from '@/locales'
interface Props {
  workflow: Workflow.WorkflowInfo
  wfNode: Workflow.WorkflowNode
}
const props = defineProps<Props>()
const nodeConfig = props.wfNode.nodeConfig as Workflow.NodeConfigMailSend

const recipientEmailInput = computed(() => props.wfNode.inputConfig.ref_inputs.find(input => input.name === 'recipient_email'))
const recipientNameInput = computed(() => props.wfNode.inputConfig.ref_inputs.find(input => input.name === 'recipient_name'))

function addRecipientInput(name: 'recipient_email' | 'recipient_name') {
  if (props.wfNode.inputConfig.ref_inputs.some(input => input.name === name))
    return

  // This workflow editor intentionally edits the shared draft node in place.
  // eslint-disable-next-line vue/no-mutating-props
  props.wfNode.inputConfig.ref_inputs.push({
    uuid: uuidv4().replace(/-/g, ''),
    name,
    node_uuid: '',
    node_param_name: '',
  })
  if (name === 'recipient_email')
    nodeConfig.to_mails = '{recipient_email}'
}

function removeRecipientInput(name: 'recipient_email' | 'recipient_name') {
  const index = props.wfNode.inputConfig.ref_inputs.findIndex(input => input.name === name)
  if (index >= 0)
    // This workflow editor intentionally edits the shared draft node in place.
    // eslint-disable-next-line vue/no-mutating-props
    props.wfNode.inputConfig.ref_inputs.splice(index, 1)
  if (name === 'recipient_email')
    nodeConfig.to_mails = ''
}

function onRecipientVariableSelected(refInput: Workflow.NodeIORefDinition, nodeUuidParamName: string[]) {
  refInput.node_uuid = nodeUuidParamName[0]
  refInput.node_param_name = nodeUuidParamName[1]
}

function onRecipientNameSelected(nodeUuidParamName: string[]) {
  if (recipientNameInput.value)
    onRecipientVariableSelected(recipientNameInput.value, nodeUuidParamName)
}

function onRecipientEmailSelected(nodeUuidParamName: string[]) {
  if (recipientEmailInput.value)
    onRecipientVariableSelected(recipientEmailInput.value, nodeUuidParamName)
}
</script>

<template>
  <div class="flex flex-col w-full">
    <div class="mt-2 border border-gray-200 rounded-md p-3 bg-gray-50">
      <div class="text-xl mb-3">{{ t('workflow.recipientInfo') }}</div>
      <div class="mb-3">
        <div class="text-sm mb-1 flex items-center justify-between">
          <span>{{ t('workflow.recipientNameFromInput') }}</span>
          <NButton v-if="recipientNameInput" text type="error" size="tiny" @click="removeRecipientInput('recipient_name')">
            {{ t('common.delete') }}
          </NButton>
        </div>
        <WfVariableSelector
          v-if="recipientNameInput" :workflow="workflow" :wf-node="wfNode" :wf-ref-var="recipientNameInput" :exclude-nodes="[wfNode.uuid]"
          @variable-selected="onRecipientNameSelected"
        />
        <NButton v-else dashed size="small" @click="addRecipientInput('recipient_name')">
          {{ t('workflow.addRecipientNameInput') }}
        </NButton>
      </div>
      <div>
        <div class="text-sm mb-1 flex items-center justify-between">
          <span>{{ t('workflow.recipientEmailFromInput') }}<span class="text-red-500 text-base">*</span></span>
          <NButton v-if="recipientEmailInput" text type="error" size="tiny" @click="removeRecipientInput('recipient_email')">
            {{ t('common.delete') }}
          </NButton>
        </div>
        <WfVariableSelector
          v-if="recipientEmailInput" :workflow="workflow" :wf-node="wfNode" :wf-ref-var="recipientEmailInput" :exclude-nodes="[wfNode.uuid]"
          @variable-selected="onRecipientEmailSelected"
        />
        <NButton v-else dashed size="small" @click="addRecipientInput('recipient_email')">
          {{ t('workflow.addRecipientEmailInput') }}
        </NButton>
      </div>
      <div class="text-xs text-gray-500 mt-2">{{ t('workflow.recipientInputTip') }}</div>
    </div>
    <NodePropertyInput
      class="mt-4" :workflow="workflow" :wf-node="wfNode"
      :exclude-variable-names="['recipient_email', 'recipient_name']"
    />
    <div class="mt-6">
      <div class="text-xl mb-1">
        {{ t('workflow.sender') }}
      </div>
      <div class="text-sm">
        <NRadioGroup v-model:value="nodeConfig.sender_type">
          <NRadio key="sys" :value="1">
            {{ t('workflow.system') }}
          </NRadio>
          <NRadio key="custom" :value="2">
            {{ t('workflow.custom') }}
          </NRadio>
        </NRadioGroup>
      </div>
    </div>
    <div v-show="nodeConfig.sender_type === 2">
      <div class="flex flex-col space-y-2 text-sm border border-gray-200 rounded-md p-2 bg-gray-100 mt-2">
        <div>{{ t('workflow.smtpServer') }}</div>
        <NInput v-model:value="nodeConfig.smtp.host" placeholder="eg: smtp.exmail.qq.com" />
        <div>{{ t('workflow.smtpPort') }}</div>
        <NInputNumber v-model:value="nodeConfig.smtp.port" />
        <div>{{ t('workflow.senderName') }}</div>
        <NInput v-model:value="nodeConfig.sender.name" />
        <div>{{ t('workflow.senderEmail') }}</div>
        <NInput v-model:value="nodeConfig.sender.mail" />
        <div>{{ t('workflow.senderPassword') }}</div>
        <NInput v-model:value="nodeConfig.sender.password" type="password" show-password-on="mousedown" />
      </div>
    </div>
    <div class="mt-6">
      <div class="text-xl mb-1 flex align-center items-center">
        {{ t('workflow.ccEmail') }}<ReferTooltip />
      </div>
      <div>
        <NInput v-model:value="nodeConfig.cc_mails" :placeholder="t('workflow.recipientEmailPlaceholder')" />
      </div>
    </div>
    <div class="mt-6">
      <div class="text-xl mb-1 flex align-center items-center">
        {{ t('workflow.emailSubject') }}
        <ReferTooltip :brief="true" />
        <span class="text-red-500 text-base">*</span>
      </div>
      <div class="flex flex-col">
        <ReferComment />
        <NInput v-model:value="nodeConfig.subject" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" />
      </div>
    </div>
    <div class="mt-6 mb-12">
      <div class="text-xl mb-1 flex align-center items-center">
        {{ t('workflow.emailContent') }}
        <ReferTooltip :brief="true" />
        <span class="text-red-500 text-base">*</span>
      </div>
      <div class="flex flex-col">
        <ReferComment />
        <NInput v-model:value="nodeConfig.content" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" />
      </div>
    </div>
  </div>
</template>
