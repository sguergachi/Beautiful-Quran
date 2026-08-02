/**
 * First-chapter lesson around the live collapsed ayah rail — Android AyahRailTip.
 */
import { ContextualFeatureTip } from '../theme/ContextualFeatureTip'
import type { AyahSelectorSide } from '../../data/settings'

type Props = {
  visible: boolean
  railSide: AyahSelectorSide
  targetCenterY: number
  surfaceWidth: number
  surfaceHeight: number
  onDismiss: () => void
  onRenderedChange?: (rendered: boolean) => void
}

export function AyahRailTip({
  visible,
  railSide,
  targetCenterY,
  surfaceWidth,
  surfaceHeight,
  onDismiss,
  onRenderedChange,
}: Props) {
  const railOnLeft = railSide === 'left'
  return (
    <ContextualFeatureTip
      visible={visible}
      title="Find any ayah"
      body="Press and drag this rail."
      onDismiss={onDismiss}
      onRenderedChange={onRenderedChange}
      spotlightSide={railSide}
      spotlightCenter={{
        x: railOnLeft ? 14 : Math.max(14, surfaceWidth - 14),
        y: targetCenterY,
      }}
      placement={{
        bodyAngleDegrees: railOnLeft ? 0 : 180,
      }}
      actionCenter={{
        x: railOnLeft ? Math.max(52, surfaceWidth - 68) : 68,
        y: Math.max(52, surfaceHeight - 72),
      }}
      contentPadding={{
        start: railOnLeft ? 8 : 20,
        end: railOnLeft ? 20 : 8,
      }}
    />
  )
}
