import {css, html, LitElement, PropertyValues, TemplateResult} from "lit";
import {customElement, property} from "lit/decorators.js";
import {CalendarEvent, ClientRole, RulesetLang, RulesetUnion, TenantRuleset, WellknownRulesetMetaItems} from "@openremote/model";
import "@openremote/or-translate";
import manager, {Util} from "@openremote/core";
import "@openremote/or-mwc-components/or-mwc-input";
import {InputType, OrInputChangedEvent} from "@openremote/or-mwc-components/or-mwc-input";
import {style as OrAssetTreeStyle} from "@openremote/or-asset-tree";
import {
    OrRules,
    OrRulesAddEvent,
    OrRulesRequestAddEvent,
    OrRulesRequestDeleteEvent,
    OrRulesRequestSelectionEvent,
    OrRulesSelectionEvent,
    RulesConfig,
    RulesetNode
} from "./index";
import "@openremote/or-mwc-components/or-mwc-menu";
import {getContentWithMenuTemplate} from "@openremote/or-mwc-components/or-mwc-menu";
import {ListItem} from "@openremote/or-mwc-components/or-mwc-list";
import {translate} from "@openremote/or-translate";
import i18next from "i18next";
import {showErrorDialog, showOkCancelDialog} from "@openremote/or-mwc-components/or-mwc-dialog";
import {GenericAxiosResponse} from "@openremote/rest";

// language=CSS
const style = css`
    :host {
        flex-grow: 1;
        font-size: var(--internal-or-rules-list-text-size);
        
        /*Override asset tree styles*/
        --internal-or-asset-tree-header-height: var(--internal-or-rules-list-header-height);
    }
    
    #wrapper {
        display: flex;
        flex-direction: column;
        height: 100%;
        width: 100%;
    }

    #wrapper[data-disabled] {
        opacity: 0.3;
        pointer-events: none;
    }

    .node-container {
        align-items: center;
        padding-left: 10px;
    }
    
    .header-ruleset-type {
        display: flex;
        align-items: center;
        height: var(--internal-or-asset-tree-header-height);
        line-height: var(--internal-or-asset-tree-header-height);
        color: var(--internal-or-asset-tree-header-text-color);
    }
    
    #header-btns {
        --or-mwc-input-color: var(--internal-or-rules-text-color);
    }
    
    .header-ruleset-type p {
        margin: 0 15px;
    }

    .node-status {
        width: 10px;
        height: 10px;
        border-radius: 5px;
        margin-right: 10px;
        display: none;
    }

    .node-language{
        padding-left: 10px;
        opacity: 50%;
    }

    .bg-green {
        background-color: #28b328;
    }

    .bg-red {
        background-color: red;
    }
    
    .bg-blue {
        background-color: #3e92dc;
    }

    .bg-grey {
        background-color: #b7b7b7;
    }
`;

@customElement("or-rule-list")
export class OrRuleList extends translate(i18next)(LitElement) {

    public static DEFAULT_ALLOWED_LANGUAGES = [RulesetLang.JSON, RulesetLang.GROOVY, RulesetLang.JAVASCRIPT, RulesetLang.FLOW];

    @property({type: Object})
    public config?: RulesConfig;

    @property({type: Boolean})
    public readonly: boolean = false;

    @property({type: Boolean})
    public disabled: boolean = false;

    @property({type: Boolean})
    public multiSelect: boolean = true;

    @property({type: Array})
    public selectedIds?: number[];

    @property({type: String})
    public sortBy?: string;

    @property({type: String})
    public language?: RulesetLang;

    @property({attribute: false})
    protected _nodes?: RulesetNode[];

    @property({attribute: false})
    protected _showLoading: boolean = true;

    @property({type: Boolean})
    protected _globalRulesets: boolean = false;

    protected _selectedNodes: RulesetNode[] = [];
    protected _ready = false;

    static get styles() {
        return [
            OrAssetTreeStyle,
            style
        ];
    }

    public async refresh() {
        this._nodes = undefined;
        await this._loadRulesets();
    }

    protected get _allowedLanguages(): RulesetLang[] | undefined {
        const languages = this.config && this.config.controls && this.config.controls.allowedLanguages ? [...this.config.controls.allowedLanguages] : OrRuleList.DEFAULT_ALLOWED_LANGUAGES;
        const groovyIndex = languages.indexOf(RulesetLang.GROOVY);
        const flowIndex = languages.indexOf(RulesetLang.FLOW);
        if(!manager.isSuperUser()) {
            if(groovyIndex > 0) languages.splice(groovyIndex,1);
        } else if (groovyIndex < 0) {
            languages.push(RulesetLang.GROOVY);
        }
        if(this._globalRulesets) {
            if(flowIndex > 0) languages.splice(flowIndex, 1);
        }
        return languages;
    }

    protected _updateLanguage() {
        const rulesetLangs = this._allowedLanguages;
        if (!rulesetLangs || rulesetLangs.length === 0) {
            this.language = undefined;
        } else if (!this.language || rulesetLangs.indexOf(this.language) < 0) {
            this.language = rulesetLangs[0];
        }
    }

    protected _onReady() {
        this._ready = true;
        this._loadRulesets();
    }

    protected firstUpdated(_changedProperties: PropertyValues): void {
        super.firstUpdated(_changedProperties);
        if (manager.ready) {
            this._onReady();
        }
    }

    public shouldUpdate(_changedProperties: PropertyValues): boolean {
        const result = super.shouldUpdate(_changedProperties);

        if (!this.sortBy) {
            this.sortBy = "name";
        }

        if (_changedProperties.has("language")) {
            this._updateLanguage();
        }

        if (_changedProperties.has("_globalRulesets")) {
            this._updateLanguage(); 
            this.refresh();
        }
        
        if (manager.ready) {
            if (!this._nodes) {
                this._loadRulesets();
                return true;
            }

            if (_changedProperties.has("selectedIds")) {
                let changed = true;
                const oldValue = _changedProperties.get("selectedIds") as number[];
                if (this.selectedIds && oldValue && this.selectedIds.length === oldValue.length) {
                    changed = !!this.selectedIds.find((oldSelected) => oldValue.indexOf(oldSelected) < 0);
                }
                if (changed) {
                    this._updateSelectedNodes();
                }
            }

            if (_changedProperties.has("sortBy")) {
                OrRuleList._updateSort(this._nodes!, this._getSortFunction());
            }
        }

        return result;
    }

    protected render() {
        if (!this.language) {
            this._updateLanguage();
        }

        const allowedLanguages = this._allowedLanguages;
        const sortOptions = ["name", "createdOn"]
        if (allowedLanguages && allowedLanguages.length > 1) sortOptions.push("lang")
        
        let addTemplate: TemplateResult | string = ``;

        if (!this._isReadonly()) {
            if (allowedLanguages && allowedLanguages.length > 1) {
                addTemplate = getContentWithMenuTemplate(
                    html`<or-mwc-input type="${InputType.BUTTON}" icon="plus"></or-mwc-input>`,
                    allowedLanguages.map((l) => {
                        return {value: l, text: i18next.t(l)} as ListItem;
                    }),
                    this.language,
                    (v) => this._onAddClicked(v as RulesetLang));
            } else {
                addTemplate = html`<or-mwc-input type="${InputType.BUTTON}" icon="plus" @click="${() => this._onAddClicked(this.language!)}"></or-mwc-input>`;
            }
        }
        return html`
            <div id="wrapper" ?data-disabled="${this.disabled}">
                ${manager.isSuperUser() ? html`
                    <div class="header-ruleset-type bg-grey">
                        <p>${i18next.t("realmRules")}</p>
                        
                        <div style="flex: 1 1 0; display: flex;">
                            <or-mwc-input style="margin: auto;" type="${InputType.SWITCH}" @or-mwc-input-changed="${(evt: OrInputChangedEvent) => this._globalRulesets = evt.detail.value}"></or-mwc-input>
                        </div>

                        <p>${i18next.t("globalRules")}</p>
                    </div>
                ` : ``}

                <div id="header">
                    <div id="title-container">
                        <or-translate id="title" value="rule_plural"></or-translate>
                    </div>
        
                    <div id="header-btns">
                        <or-mwc-input ?hidden="${this._isReadonly() || !this.selectedIds || this.selectedIds.length === 0}" type="${InputType.BUTTON}" icon="content-copy" @click="${() => this._onCopyClicked()}"></or-mwc-input>
                        <or-mwc-input ?hidden="${this._isReadonly() || !this.selectedIds || this.selectedIds.length === 0}" type="${InputType.BUTTON}" icon="delete" @click="${() => this._onDeleteClicked()}"></or-mwc-input>
                        ${addTemplate}
                        <or-mwc-input hidden type="${InputType.BUTTON}" icon="magnify" @click="${() => this._onSearchClicked()}"></or-mwc-input>
                        
                        ${getContentWithMenuTemplate(
            html`<or-mwc-input type="${InputType.BUTTON}" icon="sort-variant" ></or-mwc-input>`,
            sortOptions.map((sort) => { return { value: sort, text: i18next.t(sort) } as ListItem; }),
            this.sortBy,
            (v) => this._onSortClicked(v as string))}
                    </div>
                </div>
        
                ${!this._nodes || this._showLoading
                ? html`
                        <span id="loading"><or-translate value="loading"></or-translate></span>`
                : html`
                        <div id="list-container">
                            <ol id="list">
                                ${this._nodes.map((treeNode) => this._nodeTemplate(treeNode))}
                            </ol>
                        </div>
                    `
            }
        
                <div id="footer">
                
                </div>
            </div>
        `;
    }

    protected _isReadonly() {
        return this.readonly || !manager.hasRole(ClientRole.WRITE_RULES);
    }

    protected _nodeTemplate(node: RulesetNode): TemplateResult | string {
        return html`
            <li ?data-selected="${node.selected}" @click="${(evt: MouseEvent) => this._onNodeClicked(evt, node)}">
                <div class="node-container">
                    <span class="node-status ${OrRuleList._getNodeStatusClasses(node.ruleset)}"></span>
                    <span class="node-name">${node.ruleset.name}<span class="node-language">${node.ruleset.lang !== RulesetLang.JSON ? node.ruleset.lang : ""}</span></span>
                </div>
            </li>
        `;
    }

    protected static _getNodeStatusClasses(ruleset: RulesetUnion): string {
        let status = ruleset.enabled ? "bg-green" : "bg-red";

        if (ruleset.enabled) {

            // Look at validity meta
            if (ruleset.meta && ruleset.meta[WellknownRulesetMetaItems.VALIDITY]) {
                const calendarEvent = ruleset.meta[WellknownRulesetMetaItems.VALIDITY] as CalendarEvent;
                const now = new Date().getTime();

                if (calendarEvent.start) {
                    if (now < calendarEvent.start) {
                        // before startDate, show blue
                        status = "bg-blue";
                    } else if (calendarEvent.end && now > calendarEvent.end) {
                        // after endDate, show grey
                        status = "bg-grey";
                    } else if (calendarEvent.recurrence) {
                        // TODO: Implement RRule support
                    }
                }
            }
        }

        return status;
    }

    protected _updateSelectedNodes() {
        const actuallySelectedIds: number[] = [];
        const selectedNodes: RulesetNode[] = [];
        if (this._nodes) {
            this._nodes.forEach((node) => {
                if (this.selectedIds && this.selectedIds.indexOf(node.ruleset!.id!) >= 0) {
                    actuallySelectedIds.push(node.ruleset!.id!);
                    selectedNodes.push(node);
                    node.selected = true;
                } else {
                    node.selected = false;
                }
            });
        }

        this.selectedIds = actuallySelectedIds;
        const oldSelection = this._selectedNodes;
        this._selectedNodes = selectedNodes;
        this.dispatchEvent(new OrRulesSelectionEvent({
            oldNodes: oldSelection,
            newNodes: selectedNodes
        }));
    }

    protected static _updateSort(nodes: RulesetNode[], sortFunction: (a: RulesetNode, b: RulesetNode) => number) {
        if (!nodes) {
            return;
        }

        nodes.sort(sortFunction);
    }

    protected _onNodeClicked(evt: MouseEvent, node: RulesetNode) {
        if (evt.defaultPrevented) {
            return;
        }

        evt.preventDefault();

        let selectedNodes: RulesetNode[] = [];
        const index = this._selectedNodes.indexOf(node);
        let select = true;
        let deselectOthers = true;
        const multiSelect = !this._isReadonly() && this.multiSelect  && (!this.config || !this.config.controls || !this.config.controls.multiSelect);

        if (multiSelect && (evt.ctrlKey || evt.metaKey)) {
            deselectOthers = false;
            if (index >= 0 && this._selectedNodes && this._selectedNodes.length > 1) {
                select = false;
            }
        }

        if (deselectOthers) {
            selectedNodes = [node];
        } else if (select) {
            if (index < 0) {
                selectedNodes = [...this._selectedNodes];
                selectedNodes.push(node);
            }
        } else if (index >= 0) {
            selectedNodes = [...this._selectedNodes];
            selectedNodes.splice(index, 1);
        }

        Util.dispatchCancellableEvent(this, new OrRulesRequestSelectionEvent({
            oldNodes: this._selectedNodes,
            newNodes: selectedNodes
        })).then((detail) => {
            if (detail.allow) {
                this.selectedIds = detail.detail.newNodes.map((node) => node.ruleset.id!);
            }
        });
    }

    protected _onCopyClicked() {
        if (this._selectedNodes.length !== 1) {
            return;
        }

        const node = this._selectedNodes[0];
        const ruleset = JSON.parse(JSON.stringify(node.ruleset)) as RulesetUnion;
        delete ruleset.lastModified;
        delete ruleset.createdOn;
        delete ruleset.status;
        delete ruleset.error;
        delete ruleset.id;
        ruleset.name = ruleset.name + " copy";

        if (this.config && this.config.rulesetCopyHandler && !this.config.rulesetCopyHandler(ruleset)) {
            return;
        }

        Util.dispatchCancellableEvent(this, new OrRulesRequestAddEvent({
            ruleset: ruleset,
            sourceRuleset: node.ruleset
        })).then((detail) => {
                if (detail.allow) {
                    this.dispatchEvent(new OrRulesAddEvent(detail.detail));
                }
            });
    }

    protected _onAddClicked(lang: RulesetLang) {
        const type = this._globalRulesets ? "global": "tenant";
        const realm = manager.isSuperUser() ? manager.displayRealm : manager.config.realm;
        const ruleset: RulesetUnion = {
            id: 0,
            type: type,
            name: OrRules.DEFAULT_RULESET_NAME,
            lang: lang,
            realm: realm,
            rules: undefined
        };

        if (this.config && this.config.rulesetAddHandler && !this.config.rulesetAddHandler(ruleset)) {
            return;
        }

        // Ensure config hasn't messed with certain values
        if (type === "tenant") {
            (ruleset as TenantRuleset).realm = realm;
        }

        const detail = {
            ruleset: ruleset,
            isCopy: false
        };

        Util.dispatchCancellableEvent(this, new OrRulesRequestAddEvent(detail))
            .then((detail) => {
                if (detail.allow) {
                    this.dispatchEvent(new OrRulesAddEvent(detail.detail));
                }
            });
    }

    protected _onDeleteClicked() {
        if (this._selectedNodes.length > 0) {
            Util.dispatchCancellableEvent(this, new OrRulesRequestDeleteEvent(this._selectedNodes))
                .then((detail) => {
                    if (detail.allow) {
                        this._doDelete();
                    }
                });
        }
    }

    protected _doDelete() {

        if (!this._selectedNodes || this._selectedNodes.length === 0) {
            return;
        }

        const doDelete = async () => {
            this.disabled = true;
            const rulesetsToDelete = this._selectedNodes.map((rulesetNode) => rulesetNode.ruleset);
            let fail = false;
            
            for (const ruleset of rulesetsToDelete) {

                if (this.config && this.config.rulesetDeleteHandler && !this.config.rulesetDeleteHandler(ruleset)) {
                    continue;
                }

                try {
                    let response: GenericAxiosResponse<void>;

                    switch (ruleset.type) {
                        case "asset":
                            response = await manager.rest.api.RulesResource.deleteAssetRuleset(ruleset.id!);
                            break;
                        case "tenant":
                            response = await manager.rest.api.RulesResource.deleteTenantRuleset(ruleset.id!);
                            break;
                        case "global":
                            response = await manager.rest.api.RulesResource.deleteGlobalRuleset(ruleset.id!);
                            break;
                    }
                    
                    if (response.status !== 204) {
                        console.error("Delete ruleset returned unexpected status '" + response.status + "': " + JSON.stringify(ruleset, null, 2));
                        fail = true;
                    }
                } catch (e) {
                    console.error("Failed to delete ruleset: " + JSON.stringify(ruleset, null, 2), e);
                    fail = true;
                }
            }
            
            if (fail) {
                showErrorDialog(i18next.t("deleteAssetsFailed"));
            }

            this.disabled = false;
            this.refresh();
        };

        // Confirm deletion request
        showOkCancelDialog(i18next.t("delete"), i18next.t("deleteRulesetsConfirm"), i18next.t("delete"))
            .then((ok) => {
                if (ok) {
                    doDelete();
                }
            });
    }

    protected _onSearchClicked() {

    }

    protected _onSortClicked(sortBy: string) {
        this.sortBy = sortBy;
    }

    protected _getSortFunction(): (a: RulesetNode, b: RulesetNode) => number {
        switch (this.sortBy) {
            case "createdOn":
                return Util.sortByNumber((node: RulesetNode) => (node.ruleset as any)![this.sortBy!]);
            default:
                return Util.sortByString((node: RulesetNode) => (node.ruleset as any)![this.sortBy!]);
        }
    }

    protected _getRealm(): string | undefined {
        if (manager.isSuperUser()) {
            return manager.displayRealm;
        }

        return manager.getRealm();
    }

    protected async _loadRulesets() {
        const sortFunction = this._getSortFunction();

        if(this._globalRulesets) {
            manager.rest.api.RulesResource.getGlobalRulesets({fullyPopulate: true }).then((response: any) => {
                if (response && response.data) {
                    this._buildTreeNodes(response.data, sortFunction);
                }
            }).catch((reason: any) => {
                console.error("Error: " + reason);
            });
        } else {
            const params = {
                fullyPopulate: true,
                language:  this._allowedLanguages
            }
            try {
                const response = await manager.rest.api.RulesResource.getTenantRulesets(this._getRealm() || manager.displayRealm, params);
                if (response && response.data) {
                    this._buildTreeNodes(response.data, sortFunction);
                }
            } catch (e) {
                console.error("Error: " + e);
            }
        }
    }

    protected _buildTreeNodes(rulesets: TenantRuleset[], sortFunction: (a: RulesetNode, b: RulesetNode) => number) {
        if (!rulesets || rulesets.length === 0) {
            this._nodes = [];
        } else {

            const nodes = rulesets.map((ruleset) => {
                return {
                    ruleset: ruleset
                } as RulesetNode;
            });
            
            nodes.sort(sortFunction);
            this._nodes = nodes;
        }
        if (this.selectedIds && this.selectedIds.length > 0) {
            this._updateSelectedNodes();
        }
        this._showLoading = false;
    }
}
