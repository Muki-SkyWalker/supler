class FieldData {
  constructor(
    public id: string,
    public validationId: string,
    public json: any,
    public label: string) {

    this.name = json.name;
    this.value = json.value;
    this.path = json.path;
    this.multiple = json.multiple;
    this.type = json.type;
    this.enabled = json.enabled;
    this.validate = json.validate || {};
  }

  public name: string;
  public value: any;
  public path: string;
  public multiple: boolean;
  public type: string;
  public enabled: boolean;
  public validate: any;

  getRenderHintName(): string {
    if (this.json.render_hint) {
      return this.json.render_hint.name;
    } else {
      return null;
    }
  }
}
